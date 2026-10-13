package com.payverse.user;

import com.jayway.jsonpath.JsonPath;
import com.payverse.common.events.EventEnvelope;
import com.payverse.common.events.EventType;
import com.payverse.common.events.Topics;
import com.payverse.common.events.payload.AuthEvents;
import com.payverse.common.outbox.EventEnvelopeCodec;
import com.payverse.common.security.PayverseJwtValidators;
import com.payverse.common.test.KafkaTestcontainer;
import com.payverse.common.test.PostgresTestcontainer;
import com.payverse.common.test.RedisTestcontainer;
import com.payverse.common.test.TestJwts;
import com.payverse.user.model.TestAadhaar;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("it")
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, RedisTestcontainer.class, KafkaTestcontainer.class, UserServiceIT.TestTokens.class})
class UserServiceIT {

    /** Tokens signed by the shared test key, validated with the production validators. */
    @TestConfiguration
    static class TestTokens {
        @Bean
        JwtDecoder jwtDecoder() {
            return PayverseJwtValidators.configure((NimbusJwtDecoder) TestJwts.INSTANCE.jwtDecoder(), TestJwts.ISSUER, "payverse");
        }
    }

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    EventEnvelopeCodec codec;

    static String bearer(UUID userId) {
        return TestJwts.INSTANCE.bearer(userId);
    }

    static String randomPan() {
        String letters = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder pan = new StringBuilder();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 5; i++) {
            pan.append(letters.charAt(r.nextInt(letters.length())));
        }
        pan.append(String.format("%04d", r.nextInt(9000) + 1000).replace("9999", "1234"));
        return pan.append(letters.charAt(r.nextInt(letters.length()))).toString();
    }

    void minKyc(UUID user, String pan) throws Exception {
        mvc.perform(post("/api/v1/users/me/kyc/min").header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"documentType":"PAN","documentNumber":"%s","fullName":"Asha Verma","dateOfBirth":"1995-05-20"}
                                """.formatted(pan)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycTier").value("MIN_KYC"));
    }

    @Test
    void userRegisteredEventCreatesTheProfileExactlyOnce() throws Exception {
        UUID userId = UUID.randomUUID();
        EventEnvelope<AuthEvents.UserRegistered> envelope = EventEnvelope.create(EventType.USER_REGISTERED, "auth-service",
                userId.toString(), "corr-it", new AuthEvents.UserRegistered(userId, "******3210", null));
        String json = codec.encode(envelope);
        kafka.send(Topics.AUTH_USER_REGISTERED, userId.toString(), json).get();
        kafka.send(Topics.AUTH_USER_REGISTERED, userId.toString(), json).get(); // duplicate delivery

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                mvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(userId)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.mobileMasked").value("******3210"))
                        .andExpect(jsonPath("$.kycTier").value("NONE")));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class,
                        envelope.eventId())).isEqualTo(1));
    }

    @Test
    void profileUpdateAndValidation() throws Exception {
        UUID user = UUID.randomUUID();
        mvc.perform(put("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(user)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Asha Verma\",\"email\":\"Asha@Example.com\",\"dateOfBirth\":\"1995-05-20\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("asha@example.com"));
        String storedEmail = jdbc.queryForObject("SELECT email_enc FROM profiles WHERE user_id = ?", String.class, user);
        assertThat(storedEmail).startsWith("test:").doesNotContain("asha");

        mvc.perform(put("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(user)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_INVALID_PROFILE"));
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void kycUpgradeFlow() throws Exception {
        UUID user = UUID.randomUUID();
        String pan = randomPan();
        minKyc(user, pan);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'user.kyc-upgraded' AND published_at IS NOT NULL",
                        Integer.class, user.toString())).isEqualTo(1));
        String storedNumber = jdbc.queryForObject("SELECT id_number_enc FROM kyc_records WHERE user_id = ?", String.class, user);
        assertThat(storedNumber).doesNotContain(pan);

        // the same PAN cannot verify a second account
        mvc.perform(post("/api/v1/users/me/kyc/min").header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"PAN\",\"documentNumber\":\"%s\",\"fullName\":\"Other Person\",\"dateOfBirth\":\"1990-01-01\"}".formatted(pan)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_KYC_DOCUMENT_IN_USE"));

        MockMultipartFile document = new MockMultipartFile("document", "aadhaar.png", "image/png", PNG);
        mvc.perform(multipart("/api/v1/users/me/kyc/full").file(document).param("aadhaarNumber", TestAadhaar.random())
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycTier").value("FULL_KYC"));

        mvc.perform(get("/api/v1/users/me/kyc").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("FULL_KYC"))
                .andExpect(jsonPath("$.history.length()").value(2))
                .andExpect(jsonPath("$.history[1].documentMasked").value("******" + pan.substring(6)));
    }

    @Test
    void kycRejectionAndBadUploads() throws Exception {
        UUID user = UUID.randomUUID();
        mvc.perform(post("/api/v1/users/me/kyc/min").header(HttpHeaders.AUTHORIZATION, bearer(user)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"PAN\",\"documentNumber\":\"ABCDE9999F\",\"fullName\":\"Asha Verma\",\"dateOfBirth\":\"1995-05-20\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("USER_KYC_VERIFICATION_FAILED"));
        mvc.perform(get("/api/v1/users/me/kyc").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(jsonPath("$.tier").value("NONE"))
                .andExpect(jsonPath("$.history[0].status").value("REJECTED"));

        minKyc(user, randomPan());
        MockMultipartFile fake = new MockMultipartFile("document", "x.png", "image/png", "<html>".getBytes());
        mvc.perform(multipart("/api/v1/users/me/kyc/full").file(fake).param("aadhaarNumber", TestAadhaar.random())
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("USER_KYC_UNSUPPORTED_FILE"));
    }

    @Test
    void bankAccountsAreEncryptedMaskedAndPublished() throws Exception {
        UUID user = UUID.randomUUID();
        String first = JsonPath.read(mvc.perform(post("/api/v1/users/me/bank-accounts").header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountNumber\":\"123456789012\",\"ifsc\":\"hdfc0001234\",\"holderName\":\"Asha Verma\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maskedAccountNumber").value("XXXXXXXX9012"))
                .andExpect(jsonPath("$.ifsc").value("HDFC0001234"))
                .andExpect(jsonPath("$.primary").value(true))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/users/me/bank-accounts").header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountNumber\":\"123456789012\",\"ifsc\":\"HDFC0001234\",\"holderName\":\"Asha Verma\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/users/me/bank-accounts").header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountNumber\":\"987654321098\",\"ifsc\":\"SBIN0000001\",\"holderName\":\"Asha Verma\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.primary").value(false));

        String body = mvc.perform(get("/api/v1/users/me/bank-accounts").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("123456789012").doesNotContain("987654321098");
        List<String> stored = jdbc.queryForList("SELECT account_number_enc FROM bank_accounts WHERE user_id = ?", String.class, user);
        assertThat(stored).allSatisfy(v -> assertThat(v).startsWith("test:").doesNotContain("1234567"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'user.bank-account-linked' AND payload::text LIKE ?",
                Integer.class, "%" + user + "%")).isEqualTo(2);

        mvc.perform(delete("/api/v1/users/me/bank-accounts/" + first).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/users/me/bank-accounts").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].primary").value(true))
                .andExpect(jsonPath("$[0].bankCode").value("SBIN"));
        // another user can't see or delete it
        mvc.perform(delete("/api/v1/users/me/bank-accounts/" + first).header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void beneficiaries() throws Exception {
        UUID user = UUID.randomUUID();
        String id = JsonPath.read(mvc.perform(post("/api/v1/users/me/beneficiaries").header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"MOBILE\",\"value\":\"+91 98765 43210\",\"nickname\":\"Ravi\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.value").value("******3210"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/users/me/beneficiaries").header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"MOBILE\",\"value\":\"9876543210\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_BENEFICIARY_DUPLICATE"));
        mvc.perform(post("/api/v1/users/me/beneficiaries").header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"VPA\",\"value\":\"Ravi@PayVerse\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.value").value("ravi@payverse"));
        mvc.perform(post("/api/v1/users/me/beneficiaries").header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"TELEPATHY\",\"value\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/users/me/beneficiaries").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(delete("/api/v1/users/me/beneficiaries/" + id).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/users/me/beneficiaries").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void internalApiRequiresServiceRole() throws Exception {
        UUID user = UUID.randomUUID();
        minKyc(user, randomPan());
        String service = "Bearer " + TestJwts.INSTANCE.token("wallet-service", List.of("SERVICE"));
        mvc.perform(get("/internal/users/" + user).header(HttpHeaders.AUTHORIZATION, service))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycTier").value("MIN_KYC"))
                .andExpect(jsonPath("$.fullName").value("Asha Verma"));
        mvc.perform(get("/internal/users/" + user).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/users/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, service))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_PROFILE_NOT_FOUND"));
    }
}
