package com.payverse.transaction;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.jayway.jsonpath.JsonPath;
import com.payverse.common.security.PayverseJwtValidators;
import com.payverse.common.test.KafkaTestcontainer;
import com.payverse.common.test.PostgresTestcontainer;
import com.payverse.common.test.RedisTestcontainer;
import com.payverse.common.test.TestJwts;
import com.payverse.transaction.service.SagaRecoveryJob;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The saga end to end against real Postgres/Redis/Kafka, with WireMock playing wallet, auth, payment and user services. */
@SpringBootTest(properties = "payverse.transaction.recovery-interval-ms=3600000")
@ActiveProfiles("it")
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, RedisTestcontainer.class, KafkaTestcontainer.class, TransactionServiceIT.TestTokens.class})
class TransactionServiceIT {

    static final WireMockServer DOWNSTREAM = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        DOWNSTREAM.start();
    }

    @DynamicPropertySource
    static void downstream(DynamicPropertyRegistry registry) {
        for (String svc : List.of("wallet", "auth", "payment", "user", "fraud")) {
            registry.add("payverse.transaction." + svc + "-service-url", DOWNSTREAM::baseUrl);
        }
        registry.add("payverse.service-client.token-uri", () -> DOWNSTREAM.baseUrl() + "/api/v1/auth/oauth/token");
        registry.add("payverse.service-client.client-secret", () -> "it-secret");
    }

    @TestConfiguration
    static class TestTokens {
        @Bean
        JwtDecoder jwtDecoder() {
            return PayverseJwtValidators.configure((NimbusJwtDecoder) TestJwts.INSTANCE.jwtDecoder(), TestJwts.ISSUER, "payverse");
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SagaRecoveryJob recoveryJob;

    UUID payer;
    UUID payee;
    String payeeVpa;

    @BeforeEach
    void stubs() {
        payer = UUID.randomUUID();
        payee = UUID.randomUUID();
        payeeVpa = "ravi" + ThreadLocalRandom.current().nextInt(1_000_000) + "@payverse";
        DOWNSTREAM.resetAll();
        DOWNSTREAM.stubFor(WireMock.post("/api/v1/auth/oauth/token").willReturn(okJson(
                "{\"access_token\":\"" + TestJwts.INSTANCE.token("transaction-service", List.of("SERVICE")) + "\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
        DOWNSTREAM.stubFor(WireMock.post("/internal/auth/pin/verify").withRequestBody(matchingJsonPath("$.pin", equalTo("4826")))
                .willReturn(okJson("{\"valid\":true}")));
        DOWNSTREAM.stubFor(WireMock.post("/internal/auth/pin/verify").withRequestBody(matchingJsonPath("$.pin", equalTo("1111")))
                .willReturn(problem(422, "AUTH_PIN_INCORRECT", "\"remainingAttempts\":3")));
        DOWNSTREAM.stubFor(WireMock.get(urlMatching("/internal/users/.*")).willReturn(okJson(
                "{\"fullName\":\"Asha Verma\",\"kycTier\":\"MIN_KYC\",\"mobileMasked\":\"******3210\"}")));
        DOWNSTREAM.stubFor(WireMock.get(vpaPath(payeeVpa)).willReturn(okJson(
                "{\"vpa\":\"" + payeeVpa + "\",\"userId\":\"" + payee + "\",\"payeeName\":\"Ravi Kumar\"}")));
        DOWNSTREAM.stubFor(WireMock.post("/internal/risk/assess").willReturn(okJson(
                "{\"assessmentId\":\"" + UUID.randomUUID() + "\",\"score\":10,\"decision\":\"ALLOW\",\"reasons\":[]}")));
        stubWallet("debit", 201);
        stubWallet("credit", 201);
        stubWallet("reverse", 201);
    }

    static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder problem(int status, String code, String extra) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/problem+json")
                .withBody("{\"status\":" + status + ",\"title\":\"" + code + "\",\"detail\":\"" + code + "\",\"code\":\"" + code + "\""
                        + (extra == null ? "" : "," + extra) + "}");
    }

    static void stubWallet(String op, int status) {
        DOWNSTREAM.stubFor(WireMock.post(urlMatching("/internal/wallets/.*/" + op)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"journalId\":\"" + UUID.randomUUID() + "\",\"replayed\":false}")));
    }

    /** RestClient encodes '@' in path variables (payment-service's MVC decodes it back). */
    static String vpaPath(String vpa) {
        return "/internal/vpa/" + vpa.replace("@", "%40");
    }

    static String bearer(UUID user) {
        return TestJwts.INSTANCE.bearer(user);
    }

    ResultActions send(UUID from, String key, String pin, String body) throws Exception {
        var request = post("/api/v1/transfers").header(HttpHeaders.AUTHORIZATION, bearer(from))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(pin == null ? request : request.header("X-Txn-Pin", pin));
    }

    String toVpa(String amount) {
        return "{\"payee\":{\"type\":\"VPA\",\"value\":\"" + payeeVpa + "\"},\"amount\":" + amount + ",\"note\":\"Dinner\"}";
    }

    int walletCalls(String op, UUID txn) {
        return DOWNSTREAM.findAll(postRequestedFor(urlMatching("/internal/wallets/.*/" + op))
                .withHeader("Idempotency-Key", WireMock.matching(txn + ":.*"))).size();
    }

    @Test
    void transferToVpaSucceedsAndRetriesReplay() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = send(payer, key, "4826", toVpa("250.50")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.amount.amount").value("250.50"))
                .andExpect(jsonPath("$.payee.ref").value(payeeVpa))
                .andExpect(jsonPath("$.payee.name").value("Ravi Kumar"))
                .andReturn().getResponse().getContentAsString();
        UUID txn = UUID.fromString(JsonPath.read(body, "$.transactionId"));

        DOWNSTREAM.verify(postRequestedFor(urlEqualTo("/internal/wallets/" + payer + "/debit"))
                .withHeader("Idempotency-Key", equalTo(txn + ":debit"))
                .withRequestBody(matchingJsonPath("$.purpose", equalTo("TRANSFER"))));
        DOWNSTREAM.verify(postRequestedFor(urlEqualTo("/internal/wallets/" + payee + "/credit"))
                .withHeader("Idempotency-Key", equalTo(txn + ":credit")));

        send(payer, key, "4826", toVpa("250.50")).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.transactionId").value(txn.toString()));
        assertThat(walletCalls("debit", txn)).isEqualTo(1);
        send(payer, key, "4826", toVpa("999")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(jdbc.queryForList("SELECT event_type FROM outbox_events WHERE aggregate_id = ? ORDER BY created_at", String.class, txn.toString()))
                .containsExactly("transaction.initiated", "transaction.completed");
        mvc.perform(get("/api/v1/transactions/" + txn).header(HttpHeaders.AUTHORIZATION, bearer(payer)))
                .andExpect(jsonPath("$.transaction.direction").value("SENT"))
                .andExpect(jsonPath("$.statusHistory[*].to").value(org.hamcrest.Matchers.contains("INITIATED", "PENDING", "SUCCESS")));
        mvc.perform(get("/api/v1/transactions/" + txn).header(HttpHeaders.AUTHORIZATION, bearer(payee)))
                .andExpect(jsonPath("$.transaction.direction").value("RECEIVED"))
                .andExpect(jsonPath("$.transaction.counterparty").value("Asha Verma (******3210)"));
        mvc.perform(get("/api/v1/transactions/" + txn).header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void riskBlockStopsTheTransferBeforeAnyMoneyMoves() throws Exception {
        DOWNSTREAM.stubFor(WireMock.post("/internal/risk/assess").atPriority(1)
                .withRequestBody(matchingJsonPath("$.userId", equalTo(payer.toString())))
                .willReturn(okJson("{\"assessmentId\":\"" + UUID.randomUUID() + "\",\"score\":90,\"decision\":\"BLOCK\","
                        + "\"reasons\":[\"VELOCITY_COUNT\",\"NEW_DEVICE\"]}")));
        String body = send(payer, UUID.randomUUID().toString(), "4826", toVpa("500")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("TXN_RISK_BLOCKED"))
                .andReturn().getResponse().getContentAsString();
        UUID txn = UUID.fromString(JsonPath.read(body, "$.transactionId"));
        assertThat(walletCalls("debit", txn)).isZero();
        DOWNSTREAM.verify(postRequestedFor(urlEqualTo("/internal/risk/assess"))
                .withRequestBody(matchingJsonPath("$.transactionId", equalTo(txn.toString())))
                .withRequestBody(matchingJsonPath("$.deviceId", equalTo("test-device"))));
        assertThat(jdbc.queryForObject("SELECT risk_decision || ':' || risk_score FROM transactions WHERE id = ?", String.class, txn))
                .isEqualTo("BLOCK:90");
    }

    @Test
    void unreachableRiskServiceFailsOpenForSmallAndClosedForLargeAmounts() throws Exception {
        DOWNSTREAM.stubFor(WireMock.post("/internal/risk/assess").atPriority(1)
                .withRequestBody(matchingJsonPath("$.userId", equalTo(payer.toString())))
                .willReturn(aResponse().withStatus(503)));
        send(payer, UUID.randomUUID().toString(), "4826", toVpa("2000")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        send(payer, UUID.randomUUID().toString(), "4826", toVpa("2000.01")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("TXN_RISK_BLOCKED"));
    }

    static final String RECON = "Bearer " + TestJwts.INSTANCE.token("admin-service", List.of("SERVICE"));

    ResultActions refund(UUID txn, UUID refundId) throws Exception {
        return mvc.perform(post("/internal/transactions/{id}/refund", txn).header(HttpHeaders.AUTHORIZATION, RECON)
                .contentType(MediaType.APPLICATION_JSON).content("{\"refundId\":\"" + refundId + "\",\"reason\":\"dispute upheld\"}"));
    }

    @Test
    void approvedRefundReversesBothSidesOnce() throws Exception {
        String body = send(payer, UUID.randomUUID().toString(), "4826", toVpa("120")).andExpect(jsonPath("$.status").value("SUCCESS"))
                .andReturn().getResponse().getContentAsString();
        UUID txn = UUID.fromString(JsonPath.read(body, "$.transactionId"));
        UUID refundId = UUID.randomUUID();

        refund(txn, refundId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVERSED"))
                .andExpect(jsonPath("$.refundStatus").value("COMPLETED"));
        refund(txn, refundId).andExpect(status().isOk()).andExpect(jsonPath("$.refundStatus").value("COMPLETED"));
        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/internal/wallets/" + payee + "/reverse"))
                .withHeader("Idempotency-Key", equalTo("refund:" + refundId + ":payee")));
        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/internal/wallets/" + payer + "/reverse"))
                .withHeader("Idempotency-Key", equalTo("refund:" + refundId + ":payer")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'transaction.reversed' AND aggregate_id = ?",
                Long.class, txn.toString())).isEqualTo(1);
        refund(txn, UUID.randomUUID()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TXN_NOT_REFUNDABLE"));

        mvc.perform(get("/internal/transactions/{id}", txn).header(HttpHeaders.AUTHORIZATION, RECON))
                .andExpect(jsonPath("$.status").value("REVERSED")).andExpect(jsonPath("$.payeeUserId").value(payee.toString()));
        String window = "from=" + java.time.Instant.now().minusSeconds(600) + "&to=" + java.time.Instant.now().plusSeconds(60);
        String list = mvc.perform(get("/internal/transactions?size=1000&" + window).header(HttpHeaders.AUTHORIZATION, RECON))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(list, "$.items[*].id")).contains(txn.toString());
        mvc.perform(get("/internal/transactions/{id}", UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, RECON)).andExpect(status().isNotFound());
    }

    @Test
    void refundFailsWhenThePayeeSpentTheMoneyAndCanBeRetried() throws Exception {
        String body = send(payer, UUID.randomUUID().toString(), "4826", toVpa("80")).andReturn().getResponse().getContentAsString();
        UUID txn = UUID.fromString(JsonPath.read(body, "$.transactionId"));
        DOWNSTREAM.stubFor(WireMock.post("/internal/wallets/" + payee + "/reverse").atPriority(1)
                .willReturn(problem(422, "WALLET_INSUFFICIENT_BALANCE", null)));
        refund(txn, UUID.randomUUID()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.refundStatus").value("FAILED")).andExpect(jsonPath("$.refundFailureCode").value("WALLET_INSUFFICIENT_BALANCE"));
        DOWNSTREAM.verify(0, postRequestedFor(urlEqualTo("/internal/wallets/" + payer + "/reverse")));

        DOWNSTREAM.stubFor(WireMock.post("/internal/wallets/" + payee + "/reverse").atPriority(1).willReturn(aResponse().withStatus(503)));
        UUID retry = UUID.randomUUID();
        refund(txn, retry).andExpect(status().isAccepted()).andExpect(jsonPath("$.refundStatus").value("PENDING"));
        DOWNSTREAM.resetAll();
        stubs(); // healthy wallet again (fresh stubs)
        refund(txn, retry).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVERSED"));
    }

    @Test
    void pinAndValidationFailuresCreateNothing() throws Exception {
        send(payer, UUID.randomUUID().toString(), null, toVpa("10")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TXN_PIN_REQUIRED"));
        send(payer, UUID.randomUUID().toString(), "1111", toVpa("10")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("AUTH_PIN_INCORRECT"))
                .andExpect(jsonPath("$.remainingAttempts").value(3));
        DOWNSTREAM.stubFor(WireMock.get(vpaPath("me@payverse")).willReturn(okJson(
                "{\"vpa\":\"me@payverse\",\"userId\":\"" + payer + "\",\"payeeName\":\"Me\"}")));
        send(payer, UUID.randomUUID().toString(), "4826", "{\"payee\":{\"type\":\"VPA\",\"value\":\"me@payverse\"},\"amount\":10}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("TXN_SELF_TRANSFER"));
        send(payer, UUID.randomUUID().toString(), "4826", "{\"payee\":{\"type\":\"VPA\",\"value\":\"" + payeeVpa + "\"}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TXN_INVALID_AMOUNT"));
        send(payer, "short", "4826", toVpa("10")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions WHERE payer_user_id = ?", Integer.class, payer)).isZero();
    }

    @Test
    void refusedCreditIsCompensatedAndReportedAsFailed() throws Exception {
        UUID mobileUser = UUID.randomUUID();
        DOWNSTREAM.stubFor(WireMock.post("/internal/auth/users/lookup").willReturn(okJson(
                "{\"userId\":\"" + mobileUser + "\",\"mobileMasked\":\"******1111\"}")));
        DOWNSTREAM.stubFor(WireMock.post(urlMatching("/internal/wallets/" + mobileUser + "/credit"))
                .willReturn(problem(422, "WALLET_LIMIT_EXCEEDED", "\"limit\":\"MAX_BALANCE\"")));

        String body = send(payer, UUID.randomUUID().toString(), "4826", "{\"payee\":{\"type\":\"MOBILE\",\"value\":\"9811111111\"},\"amount\":500}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("WALLET_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.payee.ref").value("******1111"))
                .andReturn().getResponse().getContentAsString();
        UUID txn = UUID.fromString(JsonPath.read(body, "$.transactionId"));
        DOWNSTREAM.verify(postRequestedFor(urlEqualTo("/internal/wallets/" + payer + "/reverse"))
                .withHeader("Idempotency-Key", equalTo(txn + ":compensate")));
        assertThat(jdbc.queryForObject("SELECT saga_step FROM transactions WHERE id = ?", String.class, txn)).isEqualTo("COMPENSATED");
        assertThat(jdbc.queryForList("SELECT event_type FROM outbox_events WHERE aggregate_id = ? ORDER BY created_at", String.class, txn.toString()))
                .containsExactly("transaction.initiated", "transaction.failed");
    }

    @Test
    void refusedDebitFailsWithoutCrediting() throws Exception {
        DOWNSTREAM.stubFor(WireMock.post(urlMatching("/internal/wallets/" + payer + "/debit"))
                .willReturn(problem(422, "WALLET_INSUFFICIENT_BALANCE", null)));
        String body = send(payer, UUID.randomUUID().toString(), "4826", toVpa("10000")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("WALLET_INSUFFICIENT_BALANCE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(walletCalls("credit", UUID.fromString(JsonPath.read(body, "$.transactionId")))).isZero();
    }

    @Test
    void walletOutageLeavesItPendingAndRecoveryFinishesIt() throws Exception {
        DOWNSTREAM.stubFor(WireMock.post(urlMatching("/internal/wallets/" + payee + "/credit")).willReturn(aResponse().withStatus(503)));
        String body = send(payer, UUID.randomUUID().toString(), "4826", toVpa("75")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        UUID txn = UUID.fromString(JsonPath.read(body, "$.transactionId"));
        assertThat(jdbc.queryForObject("SELECT saga_step FROM transactions WHERE id = ?", String.class, txn)).isEqualTo("DEBITED");

        stubWallet("credit", 201); // wallet-service is back
        jdbc.update("UPDATE transactions SET next_attempt_at = now() - interval '1 second' WHERE id = ?", txn);
        recoveryJob.recover();

        mvc.perform(get("/api/v1/transfers/" + txn).header(HttpHeaders.AUTHORIZATION, bearer(payer)))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        assertThat(walletCalls("debit", txn)).isEqualTo(1);
    }

    @Test
    void dynamicQrCarriesTheAmount() throws Exception {
        String payload = "upi://pay?pa=" + payeeVpa + "&am=149.50&cu=INR";
        DOWNSTREAM.stubFor(WireMock.post("/internal/qr/decode").willReturn(okJson("{\"qrId\":\"" + UUID.randomUUID() + "\",\"vpa\":\"" + payeeVpa
                + "\",\"payeeUserId\":\"" + payee + "\",\"payeeName\":\"Cafe\",\"amount\":{\"amount\":\"149.50\",\"currency\":\"INR\"},\"type\":\"DYNAMIC\"}")));
        send(payer, UUID.randomUUID().toString(), "4826", "{\"payee\":{\"type\":\"QR\",\"value\":\"" + payload + "\"}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.amount.amount").value("149.50"))
                .andExpect(jsonPath("$.payee.type").value("QR"));
        send(payer, UUID.randomUUID().toString(), "4826", "{\"payee\":{\"type\":\"QR\",\"value\":\"" + payload + "\"},\"amount\":10}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("TXN_QR_AMOUNT_MISMATCH"))
                .andExpect(jsonPath("$.qrAmount").value("149.50"));
    }

    @Test
    void historyFiltersAndStatements() throws Exception {
        send(payer, UUID.randomUUID().toString(), "4826", toVpa("100")).andExpect(status().isCreated());
        send(payer, UUID.randomUUID().toString(), "4826", toVpa("2500")).andExpect(status().isCreated());
        DOWNSTREAM.stubFor(WireMock.post(urlMatching("/internal/wallets/" + payer + "/debit")).willReturn(problem(422, "WALLET_FROZEN", null)));
        send(payer, UUID.randomUUID().toString(), "4826", toVpa("5")).andExpect(jsonPath("$.status").value("FAILED"));

        mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, bearer(payer)))
                .andExpect(jsonPath("$.totalItems").value(3));
        mvc.perform(get("/api/v1/transactions?status=SUCCESS&minAmount=1000").header(HttpHeaders.AUTHORIZATION, bearer(payer)))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].amount.amount").value("2500.00"));
        mvc.perform(get("/api/v1/transactions?sort=amount&order=ASC&direction=SENT").header(HttpHeaders.AUTHORIZATION, bearer(payer)))
                .andExpect(jsonPath("$.items[0].amount.amount").value("5.00"));
        // the payee only sees money that actually arrived
        mvc.perform(get("/api/v1/transactions?direction=RECEIVED").header(HttpHeaders.AUTHORIZATION, bearer(payee)))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].failureCode").doesNotExist());

        String csv = mvc.perform(get("/api/v1/transactions/statement?from=" + java.time.Instant.now().minus(java.time.Duration.ofDays(30))).header(HttpHeaders.AUTHORIZATION, bearer(payer)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString("payverse-statement.csv")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv.lines()).hasSize(3).anyMatch(l -> l.contains("DEBIT,2500.00,INR,SUCCESS"));
        byte[] pdf = mvc.perform(get("/api/v1/transactions/statement?format=pdf&from=" + java.time.Instant.now().minus(java.time.Duration.ofDays(30))).header(HttpHeaders.AUTHORIZATION, bearer(payer)))
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        mvc.perform(get("/api/v1/transactions/statement?from=2020-01-01T00:00:00Z&to=2023-01-01T00:00:00Z").header(HttpHeaders.AUTHORIZATION, bearer(payer)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void moneyRequestIsPaidThroughTheSaga() throws Exception {
        DOWNSTREAM.stubFor(WireMock.post("/internal/auth/users/lookup").willReturn(okJson(
                "{\"userId\":\"" + payer + "\",\"mobileMasked\":\"******2222\"}")));
        // payee asks payer for money
        String created = mvc.perform(post("/api/v1/money-requests").header(HttpHeaders.AUTHORIZATION, bearer(payee))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"payer\":{\"type\":\"MOBILE\",\"value\":\"9822222222\"},\"amount\":400,\"note\":\"Movie\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.direction").value("OUTGOING"))
                .andExpect(jsonPath("$.counterparty").value("******2222"))
                .andReturn().getResponse().getContentAsString();
        String requestId = JsonPath.read(created, "$.id");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'transaction.money-requested' AND aggregate_id = ?",
                Integer.class, requestId)).isEqualTo(1);
        mvc.perform(get("/api/v1/money-requests?direction=INCOMING").header(HttpHeaders.AUTHORIZATION, bearer(payer)))
                .andExpect(jsonPath("$.items[0].id").value(requestId))
                .andExpect(jsonPath("$.items[0].direction").value("INCOMING"));

        mvc.perform(post("/api/v1/money-requests/" + requestId + "/pay").header(HttpHeaders.AUTHORIZATION, bearer(payer))
                        .header("Idempotency-Key", UUID.randomUUID().toString()).header("X-Txn-Pin", "4826"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.source").value("MONEY_REQUEST"));
        DOWNSTREAM.verify(postRequestedFor(urlEqualTo("/internal/wallets/" + payee + "/credit")));
        assertThat(jdbc.queryForObject("SELECT status FROM money_requests WHERE id = ?::uuid", String.class, requestId)).isEqualTo("PAID");
        mvc.perform(post("/api/v1/money-requests/" + requestId + "/decline").header(HttpHeaders.AUTHORIZATION, bearer(payer)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TXN_MONEY_REQUEST_NOT_PENDING"));
    }

    @Test
    void splitBillCreatesExactShares() throws Exception {
        String a = "aa" + ThreadLocalRandom.current().nextInt(1_000_000) + "@payverse";
        String b = "bb" + ThreadLocalRandom.current().nextInt(1_000_000) + "@payverse";
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        DOWNSTREAM.stubFor(WireMock.get(vpaPath(a)).willReturn(okJson("{\"vpa\":\"" + a + "\",\"userId\":\"" + userA + "\"}")));
        DOWNSTREAM.stubFor(WireMock.get(vpaPath(b)).willReturn(okJson("{\"vpa\":\"" + b + "\",\"userId\":\"" + userB + "\"}")));

        String split = mvc.perform(post("/api/v1/split-bills").header(HttpHeaders.AUTHORIZATION, bearer(payer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"total\":1000,\"note\":\"Goa trip\",\"participants\":[{\"type\":\"VPA\",\"value\":\"" + a + "\"},{\"type\":\"VPA\",\"value\":\"" + b + "\"}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.creatorShare.amount").value("333.34"))
                .andExpect(jsonPath("$.shares[*].amount.amount").value(org.hamcrest.Matchers.contains("333.33", "333.33")))
                .andExpect(jsonPath("$.shares[*].status").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("PENDING"))))
                .andReturn().getResponse().getContentAsString();
        String splitId = JsonPath.read(split, "$.id");
        mvc.perform(get("/api/v1/split-bills/" + splitId).header(HttpHeaders.AUTHORIZATION, bearer(userA))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/split-bills/" + splitId).header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID()))).andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/split-bills").header(HttpHeaders.AUTHORIZATION, bearer(payer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"total\":100,\"participants\":[{\"type\":\"VPA\",\"value\":\"" + a + "\"},{\"type\":\"VPA\",\"value\":\"" + a.toUpperCase() + "\"}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TXN_INVALID_SPLIT"));
        // an unknown participant aborts the whole split: nothing is saved
        long before = jdbc.queryForObject("SELECT count(*) FROM split_bills WHERE creator_user_id = ?", Long.class, payer);
        DOWNSTREAM.stubFor(WireMock.get(vpaPath("ghost@payverse")).willReturn(problem(404, "PAYMENT_VPA_NOT_FOUND", null)));
        mvc.perform(post("/api/v1/split-bills").header(HttpHeaders.AUTHORIZATION, bearer(payer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"total\":100,\"participants\":[{\"type\":\"VPA\",\"value\":\"" + a + "\"},{\"type\":\"VPA\",\"value\":\"ghost@payverse\"}]}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PAYMENT_VPA_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM split_bills WHERE creator_user_id = ?", Long.class, payer)).isEqualTo(before);
    }

    @Test
    void internalTransfersForCollectApprovals() throws Exception {
        String body = "{\"payerUserId\":\"" + payer + "\",\"payeeUserId\":\"" + payee + "\",\"payeeRef\":\"" + payeeVpa
                + "\",\"amount\":300,\"source\":\"COLLECT\",\"sourceId\":\"" + UUID.randomUUID() + "\"}";
        String key = "collect:" + UUID.randomUUID();
        mvc.perform(post("/internal/transfers").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwts.INSTANCE.token("payment-service", List.of("SERVICE")))
                        .header("Idempotency-Key", key).header("X-Txn-Pin", "4826").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.transactionId").exists());
        mvc.perform(post("/internal/transfers").header(HttpHeaders.AUTHORIZATION, bearer(payer))
                        .header("Idempotency-Key", key).header("X-Txn-Pin", "4826").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }
}
