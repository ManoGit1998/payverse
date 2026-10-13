package com.payverse.auth;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.JWKSet;
import com.payverse.auth.service.OtpSender;
import com.payverse.auth.model.MobileNumber;
import com.payverse.auth.model.OtpPurpose;
import com.payverse.common.test.KafkaTestcontainer;
import com.payverse.common.test.PostgresTestcontainer;
import com.payverse.common.test.RedisTestcontainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "payverse.auth.admin-mobiles=9000000001",
        "payverse.auth.service-clients[0].id=transaction-service",
        "payverse.auth.service-clients[0].secret=it-secret"
})
@ActiveProfiles("it")
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, RedisTestcontainer.class, KafkaTestcontainer.class, AuthServiceIT.CapturingOtp.class})
class AuthServiceIT {

    /** Replaces the MOCK LogOtpSender so tests can read the code. */
    @TestConfiguration
    static class CapturingOtp {

        static final Map<String, String> CODES = new ConcurrentHashMap<>();

        @Bean
        @Primary
        OtpSender capturingOtpSender() {
            return (MobileNumber mobile, String code, OtpPurpose purpose) -> CODES.put(mobile.value() + ":" + purpose, code);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    static String randomMobile() {
        return "9" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
    }

    String requestOtp(String mobile) throws Exception {
        mvc.perform(post("/api/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\":\"" + mobile + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.expiresInSeconds").value(300));
        return CapturingOtp.CODES.get(mobile + ":LOGIN");
    }

    record Session(String userId, String accessToken, String refreshToken, String deviceId, boolean newUser) {
        String bearer() {
            return "Bearer " + accessToken;
        }
    }

    Session login(String mobile, String deviceId) throws Exception {
        String otp = requestOtp(mobile);
        MvcResult result = mvc.perform(post("/api/v1/auth/otp/verify").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "203.0.113.7")
                        .content("""
                                {"mobile":"%s","otp":"%s","deviceId":"%s","deviceName":"Pixel 9"}
                                """.formatted(mobile, otp, deviceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return new Session(JsonPath.read(body, "$.userId"), JsonPath.read(body, "$.accessToken"),
                JsonPath.read(body, "$.refreshToken"), deviceId, JsonPath.read(body, "$.newUser"));
    }

    String serviceToken() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("client_id", "transaction-service")
                        .param("client_secret", "it-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.access_token");
    }

    @Test
    void signUpIssuesVerifiableTokensAndPublishesUserRegistered() throws Exception {
        String mobile = randomMobile();
        Session session = login(mobile, "device-" + UUID.randomUUID());
        assertThat(session.newUser()).isTrue();

        // the access token is verifiable with the public JWKS, exactly as the gateway does it
        String jwks = mvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(jwks).doesNotContain("\"d\"").doesNotContain("\"p\"");
        String kid = com.nimbusds.jwt.SignedJWT.parse(session.accessToken()).getHeader().getKeyID();
        var key = JWKSet.parse(jwks).getKeyByKeyId(kid).toRSAKey().toRSAPublicKey();
        Jwt jwt = NimbusJwtDecoder.withPublicKey(key).build().decode(session.accessToken());
        assertThat(jwt.getSubject()).isEqualTo(session.userId());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
        assertThat(jwt.getClaimAsString("typ")).isEqualTo("access");
        assertThat(jwt.getAudience()).containsExactly("payverse");

        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(session.userId()))
                .andExpect(jsonPath("$.pinSet").value(false));

        // mobile is encrypted at rest; the event carries only the masked number
        String stored = jdbc.queryForObject("SELECT mobile_enc FROM users WHERE id = ?::uuid", String.class, session.userId());
        assertThat(stored).startsWith("test:").doesNotContain(mobile);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Map<String, Object> event = jdbc.queryForMap(
                    "SELECT payload::text AS payload, published_at FROM outbox_events WHERE aggregate_id = ? AND event_type = 'auth.user-registered'",
                    session.userId());
            assertThat((String) event.get("payload")).contains("******" + mobile.substring(6)).doesNotContain(mobile);
            assertThat(event.get("published_at")).isNotNull();
        });
    }

    @Test
    void secondLoginIsNotNewAndNewDeviceIsReported() throws Exception {
        String mobile = randomMobile();
        Session first = login(mobile, "device-" + UUID.randomUUID());
        Session second = login(mobile, "device-" + UUID.randomUUID());
        assertThat(second.newUser()).isFalse();
        assertThat(second.userId()).isEqualTo(first.userId());
        Integer events = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'auth.new-device-login'", Integer.class, first.userId());
        assertThat(events).isEqualTo(1);

        mvc.perform(get("/api/v1/auth/devices").header(HttpHeaders.AUTHORIZATION, second.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.current == true)].deviceId").value(second.deviceId()));
    }

    @Test
    void wrongOtpIsCountedAndRequestsAreRateLimited() throws Exception {
        String mobile = randomMobile();
        String otp = requestOtp(mobile);
        String wrong = otp.equals("000000") ? "111111" : "000000";
        mvc.perform(post("/api/v1/auth/otp/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\":\"%s\",\"otp\":\"%s\",\"deviceId\":\"device-12345678\"}".formatted(mobile, wrong)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_OTP_INVALID"))
                .andExpect(jsonPath("$.remainingAttempts").value(4));

        requestOtp(mobile);
        requestOtp(mobile);
        mvc.perform(post("/api/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON).content("{\"mobile\":\"" + mobile + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH_OTP_RATE_LIMITED"));
    }

    @Test
    void invalidInputIsRejectedWithProblemDetails() throws Exception {
        mvc.perform(post("/api/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON).content("{\"mobile\":\"12345\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_MOBILE"));
        mvc.perform(post("/api/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void refreshRotatesAndReuseRevokesTheSession() throws Exception {
        Session session = login(randomMobile(), "device-" + UUID.randomUUID());
        String refreshBody = "{\"refreshToken\":\"%s\",\"deviceId\":\"%s\"}";
        String rotated = JsonPath.read(mvc.perform(post("/api/v1/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody.formatted(session.refreshToken(), session.deviceId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.refreshToken");

        mvc.perform(post("/api/v1/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody.formatted(session.refreshToken(), session.deviceId())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_REUSED"));
        // the reuse revoked the whole family, including the freshly rotated token
        mvc.perform(post("/api/v1/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody.formatted(rotated, session.deviceId())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_REFRESH_TOKEN"));
    }

    @Test
    void revokedDeviceCannotRefresh() throws Exception {
        Session session = login(randomMobile(), "device-" + UUID.randomUUID());
        mvc.perform(delete("/api/v1/auth/devices/" + session.deviceId()).header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\",\"deviceId\":\"%s\"}".formatted(session.refreshToken(), session.deviceId())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pinLifecycleWithLockoutAndReset() throws Exception {
        String mobile = randomMobile();
        Session session = login(mobile, "device-" + UUID.randomUUID());
        String service = serviceToken();

        mvc.perform(post("/api/v1/auth/pin").header(HttpHeaders.AUTHORIZATION, session.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"1234\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH_PIN_TOO_WEAK"));
        mvc.perform(post("/api/v1/auth/pin").header(HttpHeaders.AUTHORIZATION, session.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"4826\"}"))
                .andExpect(status().isCreated());
        String stored = jdbc.queryForObject("SELECT pin_hash FROM transaction_pins WHERE user_id = ?::uuid", String.class, session.userId());
        assertThat(stored).startsWith("$argon2id$");

        String verify = "{\"userId\":\"%s\",\"pin\":\"%s\"}";
        mvc.perform(post("/internal/auth/pin/verify").header(HttpHeaders.AUTHORIZATION, service)
                        .contentType(MediaType.APPLICATION_JSON).content(verify.formatted(session.userId(), "4826")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
        // users cannot call internal endpoints
        mvc.perform(post("/internal/auth/pin/verify").header(HttpHeaders.AUTHORIZATION, session.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(verify.formatted(session.userId(), "4826")))
                .andExpect(status().isForbidden());

        for (int remaining = 4; remaining >= 1; remaining--) {
            mvc.perform(post("/internal/auth/pin/verify").header(HttpHeaders.AUTHORIZATION, service)
                            .contentType(MediaType.APPLICATION_JSON).content(verify.formatted(session.userId(), "1111")))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.remainingAttempts").value(remaining));
        }
        mvc.perform(post("/internal/auth/pin/verify").header(HttpHeaders.AUTHORIZATION, service)
                        .contentType(MediaType.APPLICATION_JSON).content(verify.formatted(session.userId(), "1111")))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("AUTH_PIN_LOCKED"));
        mvc.perform(post("/internal/auth/pin/verify").header(HttpHeaders.AUTHORIZATION, service)
                        .contentType(MediaType.APPLICATION_JSON).content(verify.formatted(session.userId(), "4826")))
                .andExpect(status().isLocked());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'auth.pin-locked'",
                Integer.class, session.userId())).isEqualTo(1);

        // reset with OTP unlocks
        mvc.perform(post("/api/v1/auth/pin/reset/request").contentType(MediaType.APPLICATION_JSON).content("{\"mobile\":\"" + mobile + "\"}"))
                .andExpect(status().isAccepted());
        String otp = CapturingOtp.CODES.get(mobile + ":PIN_RESET");
        mvc.perform(post("/api/v1/auth/pin/reset/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\":\"%s\",\"otp\":\"%s\",\"newPin\":\"739104\"}".formatted(mobile, otp)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/internal/auth/pin/verify").header(HttpHeaders.AUTHORIZATION, service)
                        .contentType(MediaType.APPLICATION_JSON).content(verify.formatted(session.userId(), "739104")))
                .andExpect(status().isOk());
    }

    @Test
    void serviceTokensAndUserLookup() throws Exception {
        String mobile = randomMobile();
        Session session = login(mobile, "device-" + UUID.randomUUID());

        mvc.perform(post("/api/v1/auth/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials").param("client_id", "transaction-service").param("client_secret", "nope"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CLIENT"));

        mvc.perform(post("/internal/auth/users/lookup").header(HttpHeaders.AUTHORIZATION, serviceToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mobile\":\"+91" + mobile + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(session.userId()))
                .andExpect(jsonPath("$.mobileMasked").value("******" + mobile.substring(6)));
        mvc.perform(post("/internal/auth/users/lookup").header(HttpHeaders.AUTHORIZATION, serviceToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mobile\":\"" + randomMobile() + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUTH_USER_NOT_FOUND"));
    }

    @Test
    void configuredAdminGetsAdminRole() throws Exception {
        Session admin = login("9000000001", "device-" + UUID.randomUUID());
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(jsonPath("$.roles").value(org.hamcrest.Matchers.containsInAnyOrder("ADMIN", "USER")));
    }

    @Autowired
    com.payverse.auth.security.SigningKeyService signingKeys;

    @Test
    void keyRotationKeepsOldTokensValid() throws Exception {
        Session before = login(randomMobile(), "device-" + UUID.randomUUID());
        String newKid = signingKeys.rotate();

        Session after = login(randomMobile(), "device-" + UUID.randomUUID());
        assertThat(com.nimbusds.jwt.SignedJWT.parse(after.accessToken()).getHeader().getKeyID()).isEqualTo(newKid);
        String jwks = mvc.perform(get("/.well-known/jwks.json")).andReturn().getResponse().getContentAsString();
        assertThat(JWKSet.parse(jwks).getKeys().size()).isGreaterThanOrEqualTo(2);

        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, before.bearer())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, after.bearer())).andExpect(status().isOk());
    }

    @Test
    void protectedEndpointsNeedAToken() throws Exception {
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }
}
