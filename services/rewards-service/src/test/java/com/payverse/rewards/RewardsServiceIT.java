package com.payverse.rewards;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.jayway.jsonpath.JsonPath;
import com.payverse.common.events.Amount;
import com.payverse.common.events.EventEnvelope;
import com.payverse.common.events.EventType;
import com.payverse.common.events.payload.AuthEvents;
import com.payverse.common.events.payload.TransactionEvents;
import com.payverse.common.events.payload.UserEvents;
import com.payverse.common.events.payload.WalletEvents;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real Postgres/Redis/Kafka; WireMock plays wallet-service and auth-service's token endpoint. Uses the seeded rules. */
@SpringBootTest(properties = "payverse.rewards.credit-interval-ms=200")
@ActiveProfiles("it")
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, RedisTestcontainer.class, KafkaTestcontainer.class, RewardsServiceIT.TestTokens.class})
class RewardsServiceIT {

    static final WireMockServer WALLET = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        WALLET.start();
    }

    @DynamicPropertySource
    static void downstream(DynamicPropertyRegistry registry) {
        registry.add("payverse.rewards.wallet-service-url", WALLET::baseUrl);
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
                "{\"access_token\":\"" + TestJwts.INSTANCE.token("rewards-service", List.of("SERVICE")) + "\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
        for (String op : List.of("credit", "reverse")) {
            WALLET.stubFor(WireMock.post(urlMatching("/internal/wallets/.*/" + op)).willReturn(aResponse().withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"journalId\":\"" + UUID.randomUUID() + "\",\"replayed\":false}")));
        }
    }

    @Test
    void p2pPaymentsEarnScratchCardsUpToTheDailyCap() throws Exception {
        UUID payer = UUID.randomUUID();
        UUID firstTxn = UUID.randomUUID();
        String first = completed(firstTxn, payer, "150.00");
        send(first);
        send(first); // redelivery
        for (int i = 0; i < 3; i++) {
            send(completed(UUID.randomUUID(), payer, "200.00"));
        }
        send(completed(UUID.randomUUID(), UUID.randomUUID(), "99.99")); // below the minimum

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(count("SELECT count(*) FROM scratch_cards WHERE user_id = ?", payer)).isEqualTo(3)); // daily cap 3
        assertThat(count("SELECT count(*) FROM outbox_events WHERE event_type = 'rewards.scratch-card-issued' "
                + "AND aggregate_id IN (SELECT id::text FROM scratch_cards WHERE user_id = ?)", payer)).isEqualTo(3);

        String cards = mvc.perform(get("/api/v1/rewards/scratch-cards").header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(payer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].amount").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String cardId = JsonPath.read(cards, "$.items[0].id");

        String scratched = mvc.perform(post("/api/v1/rewards/scratch-cards/{id}/scratch", cardId)
                        .header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(payer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SCRATCHED"))
                .andReturn().getResponse().getContentAsString();
        String amount = JsonPath.read(scratched, "$.amount.amount");
        assertThat(new BigDecimal(amount)).isBetween(BigDecimal.ONE, new BigDecimal("25"));
        mvc.perform(post("/api/v1/rewards/scratch-cards/{id}/scratch", cardId).header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(payer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount.amount").value(amount)); // idempotent
        mvc.perform(post("/api/v1/rewards/scratch-cards/{id}/scratch", cardId)
                        .header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(UUID.randomUUID())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REWARDS_SCRATCH_CARD_NOT_FOUND"));

        String rewardId = JsonPath.read(scratched, "$.rewardId");
        awaitStatus(rewardId, "CREDITED");
        WALLET.verify(1, postRequestedFor(urlEqualTo("/internal/wallets/" + payer + "/credit"))
                .withHeader("Idempotency-Key", equalTo("reward:" + rewardId))
                .withRequestBody(matchingJsonPath("$.purpose", equalTo("CASHBACK")))
                .withRequestBody(matchingJsonPath("$[?(@.amount == " + amount + ")]")));
        assertThat(count("SELECT count(*) FROM outbox_events WHERE event_type = 'rewards.cashback-granted' AND aggregate_id = ?", rewardId))
                .isEqualTo(1);

        mvc.perform(get("/api/v1/rewards/me").header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(payer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCredited.amount").value(new BigDecimal(amount).setScale(2).toPlainString()))
                .andExpect(jsonPath("$.recent.length()").value(1));
    }

    @Test
    void firstAddMoneyOfFiveHundredEarnsTwentyFiveOnce() {
        UUID user = UUID.randomUUID();
        send(moneyAdded(user, "500.00"));
        send(moneyAdded(user, "800.00"));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(jdbc.queryForList("SELECT status FROM rewards WHERE user_id = ? AND rule_code = 'FIRST_ADD_MONEY'", String.class, user))
                    .containsExactly("CREDITED");
            assertThat(count("SELECT event_count FROM user_activity WHERE user_id = ?", user)).isEqualTo(2);
        });
        assertThat(jdbc.queryForObject("SELECT amount FROM rewards WHERE user_id = ?", BigDecimal.class, user)).isEqualByComparingTo("25");
    }

    @Test
    void walletRejectionsAreRetriedWithBackOff() {
        UUID user = UUID.randomUUID();
        WALLET.stubFor(WireMock.post("/internal/wallets/" + user + "/credit").atPriority(1).willReturn(aResponse().withStatus(422)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("{\"status\":422,\"title\":\"Limit\",\"code\":\"WALLET_LIMIT_EXCEEDED\"}")));
        send(moneyAdded(user, "600.00"));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT last_error FROM rewards WHERE user_id = ?", String.class, user))
                        .startsWith("WALLET_LIMIT_EXCEEDED"));
        assertThat(jdbc.queryForObject("SELECT status FROM rewards WHERE user_id = ?", String.class, user)).isEqualTo("PENDING_CREDIT");
        assertThat(jdbc.queryForObject("SELECT next_attempt_at > now() FROM rewards WHERE user_id = ?", Boolean.class, user)).isTrue();
    }

    @Test
    void couponsAreRedeemedOncePerUserWithinTheLimit() throws Exception {
        String code = "IT" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        String body = "{\"code\":\"" + code + "\",\"description\":\"IT coupon\",\"cashback\":15.00,\"validUntil\":\""
                + Instant.now().plus(Duration.ofDays(1)) + "\",\"usageLimit\":1}";
        mvc.perform(post("/api/v1/admin/coupons").header(HttpHeaders.AUTHORIZATION, SUPPORT).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/coupons").header(HttpHeaders.AUTHORIZATION, ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.cashback.amount").value("15.00"));
        mvc.perform(post("/api/v1/admin/coupons").header(HttpHeaders.AUTHORIZATION, ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REWARDS_COUPON_EXISTS"));

        UUID a = UUID.randomUUID();
        mvc.perform(get("/api/v1/rewards/coupons").header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(a)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.code == '" + code + "')]").exists());
        String reward = mvc.perform(post("/api/v1/rewards/coupons/{code}/redeem", code.toLowerCase())
                        .header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(a)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.sourceType").value("COUPON"))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/v1/rewards/coupons/{code}/redeem", code).header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(a)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REWARDS_COUPON_EXHAUSTED"));
        mvc.perform(post("/api/v1/rewards/coupons/{code}/redeem", code)
                        .header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(UUID.randomUUID())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REWARDS_COUPON_EXHAUSTED"));
        mvc.perform(post("/api/v1/rewards/coupons/NOSUCHCODE/redeem").header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(a)))
                .andExpect(status().isNotFound());
        awaitStatus(JsonPath.read(reward, "$.id"), "CREDITED");

        // the seeded coupon: once per user
        UUID b = UUID.randomUUID();
        mvc.perform(post("/api/v1/rewards/coupons/PAYVERSE10/redeem").header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(b)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/rewards/coupons/PAYVERSE10/redeem").header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(b)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REWARDS_COUPON_ALREADY_REDEEMED"));

        mvc.perform(put("/api/v1/admin/coupons/{code}/status", code).header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/api/v1/admin/coupons").header(HttpHeaders.AUTHORIZATION, SUPPORT)).andExpect(status().isOk());
    }

    @Test
    void referralPaysBothSidesWhenTheRefereeCompletesKyc() throws Exception {
        UUID referrer = UUID.randomUUID();
        send(envelope(EventType.USER_REGISTERED, referrer, new AuthEvents.UserRegistered(referrer, "98XXXXXX01", null)));
        await().atMost(Duration.ofSeconds(30)).until(() -> count("SELECT count(*) FROM referral_codes WHERE user_id = ?", referrer) == 1);
        String code = mvc.perform(get("/api/v1/rewards/referral").header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(referrer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.referrerBonus.amount").value("100.00"))
                .andReturn().getResponse().getContentAsString();
        String referralCode = JsonPath.read(code, "$.code");

        UUID referee = UUID.randomUUID();
        send(envelope(EventType.USER_REGISTERED, referee, new AuthEvents.UserRegistered(referee, "98XXXXXX02", referralCode.toLowerCase())));
        await().atMost(Duration.ofSeconds(30)).until(() -> count("SELECT count(*) FROM referrals WHERE referee_id = ?", referee) == 1);
        send(envelope(EventType.KYC_UPGRADED, referee, new UserEvents.KycUpgraded(referee, "NONE", "MIN_KYC")));
        send(envelope(EventType.KYC_UPGRADED, referee, new UserEvents.KycUpgraded(referee, "MIN_KYC", "FULL_KYC")));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("SELECT status FROM referrals WHERE referee_id = ?", String.class, referee)).isEqualTo("REWARDED");
            assertThat(jdbc.queryForList("SELECT amount::text || ':' || status FROM rewards WHERE user_id IN (?, ?) ORDER BY amount",
                    String.class, referrer, referee)).containsExactly("50.00:CREDITED", "100.00:CREDITED");
        });
        mvc.perform(get("/api/v1/rewards/referral").header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(referrer)))
                .andExpect(jsonPath("$.referred").value(1)).andExpect(jsonPath("$.rewarded").value(1));
    }

    @Test
    void reversalClawsBackCreditedCashbackAndVoidsUnscratchedCards() throws Exception {
        UUID payer = UUID.randomUUID();
        UUID scratchedTxn = UUID.randomUUID();
        UUID unscratchedTxn = UUID.randomUUID();
        send(completed(scratchedTxn, payer, "300.00"));
        send(completed(unscratchedTxn, payer, "300.00"));
        await().atMost(Duration.ofSeconds(30)).until(() -> count("SELECT count(*) FROM scratch_cards WHERE user_id = ?", payer) == 2);
        String cardId = jdbc.queryForObject("SELECT id::text FROM scratch_cards WHERE source_id = ?", String.class, scratchedTxn.toString());
        String rewardId = JsonPath.read(mvc.perform(post("/api/v1/rewards/scratch-cards/{id}/scratch", cardId)
                .header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(payer))).andReturn().getResponse().getContentAsString(), "$.rewardId");
        awaitStatus(rewardId, "CREDITED");

        send(reversed(scratchedTxn, payer));
        send(reversed(unscratchedTxn, payer));

        awaitStatus(rewardId, "CLAWED_BACK");
        WALLET.verify(1, postRequestedFor(urlEqualTo("/internal/wallets/" + payer + "/reverse"))
                .withHeader("Idempotency-Key", equalTo("reward:" + rewardId + ":clawback")));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT status FROM scratch_cards WHERE source_id = ?", String.class, unscratchedTxn.toString())).isEqualTo("VOIDED"));
        String voided = jdbc.queryForObject("SELECT id::text FROM scratch_cards WHERE source_id = ?", String.class, unscratchedTxn.toString());
        mvc.perform(post("/api/v1/rewards/scratch-cards/{id}/scratch", voided).header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(payer)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REWARDS_SCRATCH_CARD_VOIDED"));
    }

    @Test
    void adminManagesCashbackRules() throws Exception {
        String code = "IT_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String rule = "{\"code\":\"" + code + "\",\"name\":\"IT 5%\",\"trigger\":\"BILL_PAYMENT\",\"rewardType\":\"PERCENT\",\"percent\":5,"
                + "\"maxReward\":20,\"budget\":1000,\"priority\":-100,\"active\":false}";
        mvc.perform(post("/api/v1/admin/cashback-rules").header(HttpHeaders.AUTHORIZATION, ADMIN).contentType(MediaType.APPLICATION_JSON).content(rule))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.spent").value(0));
        mvc.perform(post("/api/v1/admin/cashback-rules").header(HttpHeaders.AUTHORIZATION, ADMIN).contentType(MediaType.APPLICATION_JSON).content(rule))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REWARDS_RULE_EXISTS"));
        mvc.perform(put("/api/v1/admin/cashback-rules/{code}", code).header(HttpHeaders.AUTHORIZATION, ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"IT flat\",\"trigger\":\"BILL_PAYMENT\",\"rewardType\":\"FLAT\",\"active\":false}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REWARDS_INVALID_RULE"));
        mvc.perform(put("/api/v1/admin/cashback-rules/{code}", code).header(HttpHeaders.AUTHORIZATION, ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"IT flat\",\"trigger\":\"BILL_PAYMENT\",\"rewardType\":\"FLAT\",\"flatAmount\":5,\"active\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rewardType").value("FLAT"));
        mvc.perform(get("/api/v1/admin/cashback-rules").header(HttpHeaders.AUTHORIZATION, SUPPORT))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.code == 'P2P_SCRATCH')]").exists());
        assertThat(count("SELECT count(*) FROM audit_log WHERE action LIKE 'CASHBACK_RULE_%' AND details->>'code' = ?", code)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ helpers

    String completed(UUID txn, UUID payer, String amount) {
        return envelope(EventType.TRANSACTION_COMPLETED, txn, new TransactionEvents.TransactionCompleted(txn, "P2P", payer, UUID.randomUUID(),
                new Amount(new BigDecimal(amount), "INR"), Instant.now()));
    }

    String moneyAdded(UUID user, String amount) {
        UUID journal = UUID.randomUUID();
        return envelope(EventType.MONEY_ADDED, journal, new WalletEvents.MoneyAdded(UUID.randomUUID(), user,
                new Amount(new BigDecimal(amount), "INR"), "pg-1", journal));
    }

    String reversed(UUID original, UUID payer) {
        UUID id = UUID.randomUUID();
        return envelope(EventType.TRANSACTION_REVERSED, id, new TransactionEvents.TransactionReversed(id, original, payer, UUID.randomUUID(),
                new Amount(new BigDecimal("300.00"), "INR"), "refund"));
    }

    String envelope(EventType type, UUID aggregate, Object payload) {
        return codec.encode(EventEnvelope.create(type, "it", aggregate.toString(), null, payload));
    }

    void send(String json) {
        try {
            EventEnvelope<?> envelope = codec.decode(json);
            kafka.send(envelope.topic(), envelope.aggregateId(), json).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    void awaitStatus(String rewardId, String status) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT status FROM rewards WHERE id = ?::uuid", String.class, rewardId)).isEqualTo(status));
    }

    long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
