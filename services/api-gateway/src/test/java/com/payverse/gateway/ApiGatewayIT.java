package com.payverse.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.payverse.common.test.RedisTestcontainer;
import com.payverse.common.test.TestJwts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.tracing.export.enabled=false",
        "management.otlp.metrics.export.enabled=false",
        "management.logging.export.otlp.enabled=false",
        "payverse.gateway.cors-allowed-origins=https://app.payverse.dev"
})
@Import(RedisTestcontainer.class)
class ApiGatewayIT {

    static final WireMockServer BACKEND = new WireMockServer(wireMockConfig().dynamicPort());
    static final TestJwts JWTS = TestJwts.INSTANCE;

    static {
        BACKEND.start();
    }

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        for (String service : List.of("auth", "user", "wallet", "transaction", "payment", "bill", "rewards",
                "notification", "fraud", "recon", "admin")) {
            registry.add("payverse.services." + service, BACKEND::baseUrl);
        }
        registry.add("payverse.security.issuer", () -> TestJwts.ISSUER);
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.stop();
    }

    @LocalServerPort
    int port;

    @Autowired
    ReactiveStringRedisTemplate redis;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).responseTimeout(Duration.ofSeconds(20)).build();
        BACKEND.resetAll();
        BACKEND.stubFor(get("/.well-known/jwks.json").willReturn(okJson(JWTS.jwksJson())));
        BACKEND.stubFor(get("/api/v1/wallets/me").willReturn(okJson("{\"balance\":\"100.00\"}")));
        BACKEND.stubFor(get("/api/v1/admin/users").willReturn(okJson("[]")));
        BACKEND.stubFor(post("/api/v1/auth/otp/request").willReturn(aResponse().withStatus(202)));
        BACKEND.stubFor(get("/api/v1/transactions").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        // clean rate-limiter buckets left by earlier runs (Redis container is reused)
        redis.scan(org.springframework.data.redis.core.ScanOptions.scanOptions().match("request_rate_limiter.*").build())
                .flatMap(redis::delete).blockLast(Duration.ofSeconds(5));
    }

    @Test
    void rejectsMissingTokenWithProblemDetails() {
        client.get().uri("/api/v1/wallets/me").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectHeader().exists("X-Correlation-Id")
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED")
                .jsonPath("$.correlationId").exists();
    }

    @Test
    void proxiesAuthenticatedRequestsWithCorrelationId() {
        UUID userId = UUID.randomUUID();
        client.get().uri("/api/v1/wallets/me")
                .header(HttpHeaders.AUTHORIZATION, JWTS.bearer(userId))
                .header("X-Correlation-Id", "client-corr-1")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Correlation-Id", "client-corr-1")
                .expectHeader().exists("X-RateLimit-Remaining")
                .expectHeader().valueEquals("X-Frame-Options", "DENY")
                .expectBody().jsonPath("$.balance").isEqualTo("100.00");

        BACKEND.verify(getRequestedFor(urlEqualTo("/api/v1/wallets/me"))
                .withHeader("X-Correlation-Id", equalTo("client-corr-1"))
                .withHeader(HttpHeaders.AUTHORIZATION, matching("Bearer .+")));
    }

    @Test
    void generatesCorrelationIdWhenMissingOrInvalid() {
        String id = client.get().uri("/api/v1/wallets/me")
                .header(HttpHeaders.AUTHORIZATION, JWTS.bearer(UUID.randomUUID()))
                .header("X-Correlation-Id", "<script>")
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseHeaders().getFirst("X-Correlation-Id");
        assertThat(id).hasSize(36);
        BACKEND.verify(getRequestedFor(urlEqualTo("/api/v1/wallets/me")).withHeader("X-Correlation-Id", equalTo(id)));
    }

    @Test
    void rejectsRefreshTokensAndForeignAudiences() {
        String refresh = JWTS.token(UUID.randomUUID().toString(), List.of("USER"), Map.of("typ", "refresh"), Duration.ofMinutes(5));
        client.get().uri("/api/v1/wallets/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + refresh)
                .exchange().expectStatus().isUnauthorized();
        String foreign = JWTS.token(UUID.randomUUID().toString(), List.of("USER"), Map.of("aud", "other"), Duration.ofMinutes(5));
        client.get().uri("/api/v1/wallets/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + foreign)
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/wallets/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + JWTS.expiredToken("u"))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void internalEndpointsAreNeverExposed() {
        client.get().uri("/internal/wallets/123/debit")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + JWTS.token("transaction-service", List.of("SERVICE")))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
    }

    @Test
    void adminRoutesRequireAdminRoles() {
        client.get().uri("/api/v1/admin/users").header(HttpHeaders.AUTHORIZATION, JWTS.bearer(UUID.randomUUID()))
                .exchange().expectStatus().isForbidden();
        client.get().uri("/api/v1/admin/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + JWTS.token(UUID.randomUUID().toString(), List.of("ADMIN")))
                .exchange().expectStatus().isOk();
    }

    @Test
    void otpEndpointsArePublicButStrictlyRateLimitedPerIp() {
        for (int i = 0; i < 5; i++) {
            client.post().uri("/api/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                    .exchange().expectStatus().isAccepted();
        }
        client.post().uri("/api/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isEqualTo(429);
    }

    @Test
    void downstreamFailureFallsBackTo503Problem() {
        client.get().uri("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, JWTS.bearer(UUID.randomUUID()))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Retry-After", "5")
                .expectBody().jsonPath("$.code").isEqualTo("DOWNSTREAM_UNAVAILABLE")
                .jsonPath("$.detail").value(d -> assertThat((String) d).contains("transaction-service"));
    }

    @Test
    void corsPreflightAllowsConfiguredOriginsOnly() {
        client.options().uri("/api/v1/wallets/me")
                .header(HttpHeaders.ORIGIN, "https://app.payverse.dev")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Idempotency-Key,Authorization")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://app.payverse.dev");

        client.options().uri("/api/v1/wallets/me")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void healthIsPublic() {
        client.get().uri("/actuator/health/liveness").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }
}
