package com.payverse.fraud;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.jayway.jsonpath.JsonPath;
import com.payverse.common.events.Amount;
import com.payverse.common.events.EventEnvelope;
import com.payverse.common.events.EventType;
import com.payverse.common.events.payload.AuthEvents;
import com.payverse.common.events.payload.TransactionEvents;
import com.payverse.common.outbox.EventEnvelopeCodec;
import com.payverse.common.security.PayverseJwtValidators;
import com.payverse.common.test.KafkaTestcontainer;
import com.payverse.common.test.PostgresTestcontainer;
import com.payverse.common.test.RedisTestcontainer;
import com.payverse.common.test.TestJwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real Postgres/Redis/Kafka; WireMock plays wallet-service (freeze) and the token endpoint. Seeded rules. */
@SpringBootTest
@ActiveProfiles("it")
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, RedisTestcontainer.class, KafkaTestcontainer.class, FraudRiskServiceIT.TestTokens.class})
class FraudRiskServiceIT {

    static final WireMockServer WALLET = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        WALLET.start();
    }

    @DynamicPropertySource
    static void downstream(DynamicPropertyRegistry registry) {
        registry.add("payverse.fraud.wallet-service-url", WALLET::baseUrl);
        registry.add("payverse.service-client.token-uri", () -> WALLET.baseUrl() + "/api/v1/auth/oauth/token");
        registry.add("payverse.service-client.client-secret", () -> "it-secret");
    }

    @TestConfiguration
    static class TestTokens {
        @Bean
        JwtDecoder jwtDecoder() {
            return PayverseJwtValidators.configure((NimbusJwtDecoder) TestJwts.INSTANCE.jwtDecoder(), TestJwts.ISSUER, "payverse");
        }
    }

    static final String SERVICE = "Bearer " + TestJwts.INSTANCE.token("transaction-service", List.of("SERVICE"));
    static final String ADMIN = "Bearer " + TestJwts.INSTANCE.token("admin-1", List.of("ADMIN"));
    static final String SUPPORT = "Bearer " + TestJwts.INSTANCE.token("support-1", List.of("SUPPORT"));

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    EventEnvelopeCodec codec;

    @BeforeEach
    void stubs() {
        WALLET.resetAll();
        WALLET.stubFor(WireMock.post("/api/v1/auth/oauth/token").willReturn(okJson(
                "{\"access_token\":\"" + TestJwts.INSTANCE.token("fraud-risk-service", List.of("SERVICE")) + "\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
        WALLET.stubFor(WireMock.post(urlMatching("/internal/wallets/.*/freeze")).willReturn(okJson("{\"status\":\"FROZEN\"}")));
    }

    ResultActions assess(UUID txn, UUID user, String amount, String device) throws Exception {
        String body = "{\"transactionId\":\"" + txn + "\",\"type\":\"P2P\",\"userId\":\"" + user + "\",\"payeeUserId\":\"" + UUID.randomUUID()
                + "\",\"amount\":" + amount + ",\"currency\":\"INR\"" + (device == null ? "" : ",\"deviceId\":\"" + device + "\"") + "}";
        return mvc.perform(post("/internal/risk/assess").header(HttpHeaders.AUTHORIZATION, SERVICE)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void ordinaryTransferIsAllowedAndAssessingIsIdempotent() throws Exception {
        UUID user = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        String first = assess(txn, user, "250.00", "phone-1").andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("ALLOW")).andExpect(jsonPath("$.score").value(0))
                .andReturn().getResponse().getContentAsString();
        assess(txn, user, "99999", "phone-1").andExpect(jsonPath("$.assessmentId").value((String) JsonPath.read(first, "$.assessmentId")))
                .andExpect(jsonPath("$.decision").value("ALLOW"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM risk_assessments WHERE transaction_id = ?", Long.class, txn)).isEqualTo(1);
        mvc.perform(post("/internal/risk/assess").header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }

    @Test
    void burstOfTransfersFromANewDeviceIsBlockedFlaggedAndPublished() throws Exception {
        UUID user = UUID.randomUUID();
        send(EventType.NEW_DEVICE_LOGIN, user, new AuthEvents.NewDeviceLogin(user, "new-phone", "Pixel", "10.0.0.1"));
        await().atMost(Duration.ofSeconds(30)).until(() ->
                jdbc.queryForObject("SELECT count(*) FROM known_devices WHERE user_id = ?", Long.class, user) == 1);

        for (int i = 0; i < 5; i++) {
            assess(UUID.randomUUID(), user, "100", "new-phone").andExpect(jsonPath("$.decision").value("ALLOW"));
        }
        // 6th within 10 minutes (VELOCITY_COUNT 40) + INR 2,000 from a device first seen today (NEW_DEVICE 50) = 90
        UUID blocked = UUID.randomUUID();
        assess(blocked, user, "2500", "new-phone").andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("BLOCK")).andExpect(jsonPath("$.score").value(90))
                .andExpect(jsonPath("$.reasons", containsInAnyOrder("NEW_DEVICE", "VELOCITY_COUNT")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'fraud.transaction-flagged' AND aggregate_id = ?",
                Long.class, blocked.toString())).isEqualTo(1);

        String flags = mvc.perform(get("/api/v1/admin/risk/flags?size=100").header(HttpHeaders.AUTHORIZATION, SUPPORT))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(flags, "$.items[?(@.transactionId == '" + blocked + "')].id");
        assertThat(ids).hasSize(1);
        String flagId = ids.getFirst();

        String resolve = "{\"resolution\":\"CONFIRMED_FRAUD\",\"note\":\"account takeover\",\"freezeWallet\":true}";
        mvc.perform(post("/api/v1/admin/risk/flags/{id}/resolve", flagId).header(HttpHeaders.AUTHORIZATION, SUPPORT)
                .contentType(MediaType.APPLICATION_JSON).content(resolve)).andExpect(status().isForbidden());
        WALLET.stubFor(WireMock.post("/internal/wallets/" + user + "/freeze").atPriority(1).willReturn(aResponse().withStatus(503)));
        mvc.perform(post("/api/v1/admin/risk/flags/{id}/resolve", flagId).header(HttpHeaders.AUTHORIZATION, ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content(resolve)).andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("SELECT status FROM flags WHERE id = ?::uuid", String.class, flagId)).isEqualTo("OPEN");

        WALLET.resetAll();
        stubs();
        mvc.perform(post("/api/v1/admin/risk/flags/{id}/resolve", flagId).header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(resolve))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED_FRAUD"))
                .andExpect(jsonPath("$.walletFrozen").value(true));
        WALLET.verify(1, postRequestedFor(urlEqualTo("/internal/wallets/" + user + "/freeze")));
        mvc.perform(post("/api/v1/admin/risk/flags/{id}/resolve", flagId).header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"resolution\":\"FALSE_POSITIVE\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FRAUD_FLAG_ALREADY_RESOLVED"));
    }

    @Test
    void pinLockAndAmountAnomalyRaiseAReviewFlag() throws Exception {
        UUID user = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            UUID txn = UUID.randomUUID();
            send(EventType.TRANSACTION_COMPLETED, txn, new TransactionEvents.TransactionCompleted(txn, "P2P", user, UUID.randomUUID(),
                    new Amount(new BigDecimal("200.00"), "INR"), Instant.now()));
        }
        send(EventType.PIN_LOCKED, user, new AuthEvents.PinLocked(user, Instant.now().plusSeconds(900)));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT completed_count || ':' || (last_pin_lock_at IS NOT NULL) FROM user_risk_profiles WHERE user_id = ?", String.class, user))
                .isEqualTo("3:true"));

        // INR 1,500 > 5 x 200 average (AMOUNT_ANOMALY 35) + PIN locked today (RECENT_PIN_LOCK 30) = 65 → REVIEW (allowed, flagged)
        UUID txn = UUID.randomUUID();
        assess(txn, user, "1500", "old-phone").andExpect(jsonPath("$.decision").value("REVIEW")).andExpect(jsonPath("$.score").value(65));
        mvc.perform(get("/api/v1/admin/risk/assessments?userId=" + user).header(HttpHeaders.AUTHORIZATION, SUPPORT))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].transactionId").value(txn.toString()))
                .andExpect(jsonPath("$.items[0].modelVersion").value("rules-v1"));
        String flagId = jdbc.queryForObject("SELECT id::text FROM flags WHERE transaction_id = ?", String.class, txn);
        mvc.perform(post("/api/v1/admin/risk/flags/{id}/resolve", flagId).header(HttpHeaders.AUTHORIZATION, SUPPORT)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"resolution\":\"FALSE_POSITIVE\",\"note\":\"customer confirmed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.walletFrozen").value(false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'RISK_FLAG_RESOLVED' AND entity_id = ?", Long.class, flagId))
                .isEqualTo(1);
    }

    @Test
    void adminTunesRules() throws Exception {
        mvc.perform(get("/api/v1/admin/risk/rules").header(HttpHeaders.AUTHORIZATION, SUPPORT))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(6));
        mvc.perform(put("/api/v1/admin/risk/rules/LARGE_AMOUNT").header(HttpHeaders.AUTHORIZATION, SUPPORT)
                .contentType(MediaType.APPLICATION_JSON).content("{\"weight\":25,\"enabled\":true}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/risk/rules/LARGE_AMOUNT").header(HttpHeaders.AUTHORIZATION, ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"weight\":25,\"enabled\":true,\"windowSeconds\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FRAUD_INVALID_RULE"));
        mvc.perform(put("/api/v1/admin/risk/rules/NOPE").header(HttpHeaders.AUTHORIZATION, ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"weight\":25,\"enabled\":true}")).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/admin/risk/rules/LARGE_AMOUNT").header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"weight\":25,\"enabled\":true,\"thresholdAmount\":50000}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.thresholdAmount").value(50000));
    }

    void send(EventType type, UUID aggregate, Object payload) {
        EventEnvelope<?> envelope = EventEnvelope.create(type, "it", aggregate.toString(), null, payload);
        try {
            kafka.send(envelope.topic(), envelope.aggregateId(), codec.encode(envelope)).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
