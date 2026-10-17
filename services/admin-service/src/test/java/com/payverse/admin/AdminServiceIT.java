package com.payverse.admin;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.jayway.jsonpath.JsonPath;
import com.payverse.admin.service.RefundService;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

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
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real Postgres/Redis/Kafka; WireMock plays auth, user, wallet and transaction services. */
@SpringBootTest(properties = "payverse.admin.refund-retry-interval-ms=500")
@ActiveProfiles("it")
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, RedisTestcontainer.class, KafkaTestcontainer.class, AdminServiceIT.TestTokens.class})
class AdminServiceIT {

    static final WireMockServer DOWNSTREAM = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        DOWNSTREAM.start();
    }

    @DynamicPropertySource
    static void downstream(DynamicPropertyRegistry registry) {
        for (String svc : List.of("auth", "user", "wallet", "transaction")) {
            registry.add("payverse.admin." + svc + "-service-url", DOWNSTREAM::baseUrl);
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

    static final String ADMIN_1 = "Bearer " + TestJwts.INSTANCE.token("admin-1", List.of("ADMIN"));
    static final String ADMIN_2 = "Bearer " + TestJwts.INSTANCE.token("admin-2", List.of("ADMIN"));
    static final String SUPPORT = "Bearer " + TestJwts.INSTANCE.token("support-1", List.of("SUPPORT"));

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RefundService refunds;

    UUID payer;
    UUID payee;
    UUID txn;

    @BeforeEach
    void stubs() {
        payer = UUID.randomUUID();
        payee = UUID.randomUUID();
        txn = UUID.randomUUID();
        DOWNSTREAM.resetAll();
        DOWNSTREAM.stubFor(WireMock.post("/api/v1/auth/oauth/token").willReturn(okJson(
                "{\"access_token\":\"" + TestJwts.INSTANCE.token("admin-service", List.of("SERVICE")) + "\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
        stubTransaction(txn, "SUCCESS", null, null);
    }

    String transaction(UUID id, String status, String refundId, String refundStatus) {
        return "{\"id\":\"" + id + "\",\"type\":\"P2P\",\"status\":\"" + status + "\",\"payerUserId\":\"" + payer + "\",\"payeeUserId\":\"" + payee
                + "\",\"payeeRef\":\"ravi@payverse\",\"amount\":250.00,\"currency\":\"INR\",\"createdAt\":\"" + Instant.now() + "\""
                + (refundId == null ? "" : ",\"refundId\":\"" + refundId + "\",\"refundStatus\":\"" + refundStatus + "\"") + "}";
    }

    void stubTransaction(UUID id, String status, String refundId, String refundStatus) {
        DOWNSTREAM.stubFor(WireMock.get("/internal/transactions/" + id).willReturn(okJson(transaction(id, status, refundId, refundStatus))));
    }

    void stubRefund(String body, int status) {
        DOWNSTREAM.stubFor(WireMock.post("/internal/transactions/" + txn + "/refund").atPriority(1)
                .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
    }

    ResultActions json(String method, String path, String auth, String body) throws Exception {
        var req = (method.equals("POST") ? post(path) : get(path)).header(HttpHeaders.AUTHORIZATION, auth);
        return mvc.perform(body == null ? req : req.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void disputeResolvedWithARefundThatAnotherAdminApproves() throws Exception {
        String dispute = json("POST", "/api/v1/disputes", TestJwts.INSTANCE.bearer(payer),
                "{\"transactionId\":\"" + txn + "\",\"reason\":\"Paid the wrong person\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("OPEN")).andReturn().getResponse().getContentAsString();
        String disputeId = JsonPath.read(dispute, "$.id");
        json("POST", "/api/v1/disputes", TestJwts.INSTANCE.bearer(payer), "{\"transactionId\":\"" + txn + "\",\"reason\":\"again\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ADMIN_DISPUTE_EXISTS"));
        json("POST", "/api/v1/disputes", TestJwts.INSTANCE.bearer(UUID.randomUUID()), "{\"transactionId\":\"" + txn + "\",\"reason\":\"not mine\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ADMIN_INVALID_DISPUTE"));
        json("GET", "/api/v1/disputes", TestJwts.INSTANCE.bearer(payer), null).andExpect(jsonPath("$[0].id").value(disputeId));
        json("GET", "/api/v1/admin/disputes", TestJwts.INSTANCE.bearer(payer), null).andExpect(status().isForbidden());

        json("POST", "/api/v1/admin/disputes/" + disputeId + "/assign", SUPPORT, null).andExpect(jsonPath("$.status").value("IN_REVIEW"))
                .andExpect(jsonPath("$.assignee").value("support-1"));
        String resolved = json("POST", "/api/v1/admin/disputes/" + disputeId + "/resolve", SUPPORT,
                "{\"outcome\":\"REFUND\",\"note\":\"Confirmed with the payee\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED")).andExpect(jsonPath("$.refundId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String refundId = JsonPath.read(resolved, "$.refundId");
        json("GET", "/api/v1/admin/refunds/" + refundId, SUPPORT, null).andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.amount").value("250.00")).andExpect(jsonPath("$.requestedBy").value("support-1"));

        json("POST", "/api/v1/admin/refunds/" + refundId + "/approve", SUPPORT, null).andExpect(status().isForbidden());
        stubRefund(transaction(txn, "REVERSED", refundId, "COMPLETED"), 200);
        json("POST", "/api/v1/admin/refunds/" + refundId + "/approve", ADMIN_1, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.decidedBy").value("admin-1"));
        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/internal/transactions/" + txn + "/refund"))
                .withRequestBody(matchingJsonPath("$.refundId", equalTo(refundId))));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'admin.refund-approved' AND aggregate_id = ?",
                Long.class, refundId)).isEqualTo(1);
        json("POST", "/api/v1/admin/refunds/" + refundId + "/approve", ADMIN_2, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ADMIN_REFUND_DECIDED"));

        String trail = json("GET", "/api/v1/admin/audit-logs?entityId=" + disputeId, SUPPORT, null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(trail, "$[*].action")).containsExactly("DISPUTE_RESOLVED", "DISPUTE_ASSIGNED", "DISPUTE_OPENED");
        String approvals = json("GET", "/api/v1/admin/audit-logs?actor=admin-1&action=REFUND_APPROVED&size=500", SUPPORT, null)
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(approvals, "$[*].entityId")).contains(refundId);
    }

    @Test
    void makerCannotCheckTheirOwnRefundAndFailuresAreRecorded() throws Exception {
        String body = json("POST", "/api/v1/admin/refunds", ADMIN_1, "{\"transactionId\":\"" + txn + "\",\"reason\":\"duplicate charge\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String refundId = JsonPath.read(body, "$.id");
        json("POST", "/api/v1/admin/refunds", SUPPORT, "{\"transactionId\":\"" + txn + "\",\"reason\":\"again\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ADMIN_REFUND_EXISTS"));
        json("POST", "/api/v1/admin/refunds/" + refundId + "/approve", ADMIN_1, null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_MAKER_CHECKER"));

        stubRefund(transaction(txn, "SUCCESS", refundId, "FAILED").replace("}", ",\"refundFailureCode\":\"WALLET_INSUFFICIENT_BALANCE\"}"), 200);
        json("POST", "/api/v1/admin/refunds/" + refundId + "/approve", ADMIN_2, null)
                .andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.failureCode").value("WALLET_INSUFFICIENT_BALANCE"));

        UUID other = UUID.randomUUID();
        stubTransaction(other, "FAILED", null, null);
        json("POST", "/api/v1/admin/refunds", SUPPORT, "{\"transactionId\":\"" + other + "\",\"reason\":\"x\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("ADMIN_NOT_REFUNDABLE"));
    }

    @Test
    void unreachableTransactionServiceIsRetriedAndRejectionsEndTheRequest() throws Exception {
        String body = json("POST", "/api/v1/admin/refunds", SUPPORT, "{\"transactionId\":\"" + txn + "\",\"reason\":\"wrong payee\"}")
                .andReturn().getResponse().getContentAsString();
        String refundId = JsonPath.read(body, "$.id");
        stubRefund("{}", 503);
        json("POST", "/api/v1/admin/refunds/" + refundId + "/approve", ADMIN_1, null).andExpect(jsonPath("$.status").value("PROCESSING"));
        stubRefund(transaction(txn, "REVERSED", refundId, "COMPLETED"), 200);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT status FROM refund_requests WHERE id = ?::uuid", String.class, refundId)).isEqualTo("COMPLETED"));
        json("GET", "/api/v1/admin/refunds?status=COMPLETED&size=100", SUPPORT, null).andExpect(status().isOk());

        UUID second = UUID.randomUUID();
        stubTransaction(second, "SUCCESS", null, null);
        String other = json("POST", "/api/v1/admin/refunds", SUPPORT, "{\"transactionId\":\"" + second + "\",\"reason\":\"x\"}")
                .andReturn().getResponse().getContentAsString();
        json("POST", "/api/v1/admin/refunds/" + JsonPath.read(other, "$.id") + "/reject", ADMIN_1, "{\"note\":\"not eligible\"}")
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void userSearchWalletControlsAndKeyRotation() throws Exception {
        UUID user = UUID.randomUUID();
        DOWNSTREAM.stubFor(WireMock.post("/internal/auth/users/lookup").withRequestBody(matchingJsonPath("$.mobile", equalTo("9876543210")))
                .willReturn(okJson("{\"userId\":\"" + user + "\",\"mobileMasked\":\"******3210\"}")));
        DOWNSTREAM.stubFor(WireMock.post("/internal/auth/users/lookup").withRequestBody(matchingJsonPath("$.mobile", equalTo("9000000009")))
                .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"status\":404,\"code\":\"AUTH_USER_NOT_FOUND\"}")));
        DOWNSTREAM.stubFor(WireMock.get("/internal/users/" + user).willReturn(okJson(
                "{\"userId\":\"" + user + "\",\"fullName\":\"Asha Verma\",\"kycTier\":\"MIN_KYC\",\"mobileMasked\":\"******3210\"}")));
        DOWNSTREAM.stubFor(WireMock.get(urlPathEqualTo("/internal/users/search")).willReturn(okJson(
                "[{\"userId\":\"" + user + "\",\"fullName\":\"Asha Verma\",\"kycTier\":\"MIN_KYC\",\"mobileMasked\":\"******3210\"}]")));
        String wallet = "{\"walletId\":\"" + UUID.randomUUID() + "\",\"userId\":\"" + user + "\",\"balance\":{\"amount\":\"120.00\",\"currency\":\"INR\"},"
                + "\"status\":\"%s\",\"kycTier\":\"MIN_KYC\"}";
        DOWNSTREAM.stubFor(WireMock.get("/internal/wallets/" + user).willReturn(okJson(wallet.formatted("ACTIVE"))));
        DOWNSTREAM.stubFor(WireMock.post("/internal/wallets/" + user + "/freeze").willReturn(okJson(wallet.formatted("FROZEN"))));
        DOWNSTREAM.stubFor(WireMock.post("/internal/wallets/" + user + "/unfreeze").willReturn(okJson(wallet.formatted("ACTIVE"))));
        DOWNSTREAM.stubFor(WireMock.post("/internal/auth/signing-keys/rotate").willReturn(okJson("{\"kid\":\"kid-2\"}")));

        json("GET", "/api/v1/admin/users?mobile=9876543210", SUPPORT, null).andExpect(jsonPath("$[0].fullName").value("Asha Verma"))
                .andExpect(jsonPath("$[0].wallet.balance.amount").value("120.00"));
        json("GET", "/api/v1/admin/users?mobile=9000000009", SUPPORT, null).andExpect(jsonPath("$.length()").value(0));
        json("GET", "/api/v1/admin/users?name=asha&kycTier=MIN_KYC", SUPPORT, null).andExpect(jsonPath("$[0].userId").value(user.toString()));
        json("GET", "/api/v1/admin/users", SUPPORT, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ADMIN_INVALID_SEARCH"));
        json("GET", "/api/v1/admin/users/" + user, SUPPORT, null).andExpect(jsonPath("$.kycTier").value("MIN_KYC"));
        DOWNSTREAM.stubFor(WireMock.get(urlPathEqualTo("/internal/users/" + UUID.fromString("00000000-0000-0000-0000-000000000001")))
                .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/problem+json").withBody("{\"status\":404,\"code\":\"USER_PROFILE_NOT_FOUND\"}")));
        DOWNSTREAM.stubFor(WireMock.get(urlPathEqualTo("/internal/wallets/" + UUID.fromString("00000000-0000-0000-0000-000000000001")))
                .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/problem+json").withBody("{\"status\":404,\"code\":\"WALLET_NOT_FOUND\"}")));
        json("GET", "/api/v1/admin/users/00000000-0000-0000-0000-000000000001", SUPPORT, null).andExpect(status().isNotFound());

        json("POST", "/api/v1/admin/wallets/" + user + "/freeze", SUPPORT, "{\"note\":\"suspicious\"}").andExpect(status().isForbidden());
        json("POST", "/api/v1/admin/wallets/" + user + "/freeze", ADMIN_1, "{\"note\":\"suspicious activity\"}")
                .andExpect(jsonPath("$.status").value("FROZEN"));
        json("POST", "/api/v1/admin/wallets/" + user + "/unfreeze", ADMIN_1, null).andExpect(jsonPath("$.status").value("ACTIVE"));
        json("POST", "/api/v1/admin/security/signing-keys/rotate", ADMIN_1, null).andExpect(jsonPath("$.kid").value("kid-2"));
        String trail = json("GET", "/api/v1/admin/audit-logs?entityId=" + user, SUPPORT, null).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(trail, "$[*].action")).containsExactly("WALLET_UNFROZEN", "WALLET_FROZEN");
        assertThat(JsonPath.<List<String>>read(trail, "$[*].actor")).containsOnly("admin-1");
    }
}
