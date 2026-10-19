package com.payverse.bill;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.jayway.jsonpath.JsonPath;
import com.payverse.bill.service.BillPaymentProcessor;
import com.payverse.bill.service.MandateService;
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
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real Postgres/Redis/Kafka; WireMock plays wallet-service and auth-service (PIN, token). Seeded MOCK billers. */
@SpringBootTest(properties = {"payverse.bill.recovery-interval-ms=500", "payverse.bill.mandate-interval-ms=3600000"})
@ActiveProfiles("it")
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, RedisTestcontainer.class, KafkaTestcontainer.class, BillPaymentServiceIT.TestTokens.class})
class BillPaymentServiceIT {

    static final WireMockServer DOWNSTREAM = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        DOWNSTREAM.start();
    }

    @DynamicPropertySource
    static void downstream(DynamicPropertyRegistry registry) {
        registry.add("payverse.bill.wallet-service-url", DOWNSTREAM::baseUrl);
        registry.add("payverse.bill.auth-service-url", DOWNSTREAM::baseUrl);
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
    MandateService mandates;

    @Autowired
    BillPaymentProcessor processor;

    UUID user;
    String consumer;

    @BeforeEach
    void stubs() {
        user = UUID.randomUUID();
        consumer = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L)).replace("0000", "1111");
        DOWNSTREAM.resetAll();
        DOWNSTREAM.stubFor(WireMock.post("/api/v1/auth/oauth/token").willReturn(okJson(
                "{\"access_token\":\"" + TestJwts.INSTANCE.token("bill-payment-service", List.of("SERVICE")) + "\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
        DOWNSTREAM.stubFor(WireMock.post("/internal/auth/pin/verify").withRequestBody(matchingJsonPath("$.pin", equalTo("4826")))
                .willReturn(okJson("{\"valid\":true}")));
        DOWNSTREAM.stubFor(WireMock.post("/internal/auth/pin/verify").withRequestBody(matchingJsonPath("$.pin", equalTo("1111")))
                .willReturn(problem(422, "AUTH_PIN_INCORRECT")));
        for (String op : List.of("debit", "credit")) {
            DOWNSTREAM.stubFor(WireMock.post(urlMatching("/internal/wallets/.*/" + op)).willReturn(aResponse().withStatus(201)
                    .withHeader("Content-Type", "application/json").withBody("{\"journalId\":\"" + UUID.randomUUID() + "\",\"replayed\":false}")));
        }
    }

    static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder problem(int status, String code) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/problem+json")
                .withBody("{\"status\":" + status + ",\"title\":\"" + code + "\",\"code\":\"" + code + "\"}");
    }

    ResultActions pay(String key, String pin, String biller, String account, String amount) throws Exception {
        var request = post("/api/v1/bills/pay").header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(user))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"billerCode\":\"" + biller + "\",\"accountRef\":\"" + account + "\",\"amount\":" + amount + "}");
        return mvc.perform(pin == null ? request : request.header("X-Txn-Pin", pin));
    }

    @Test
    void fetchAndPayAnElectricityBill() throws Exception {
        mvc.perform(get("/api/v1/billers?category=ELECTRICITY").header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        String bill = mvc.perform(post("/api/v1/bills/fetch").header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"billerCode\":\"CITY_POWER\",\"accountRef\":\"" + consumer + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.billNumber").exists())
                .andReturn().getResponse().getContentAsString();
        String due = JsonPath.read(bill, "$.amountDue.amount");

        String key = UUID.randomUUID().toString();
        String body = pay(key, "4826", "CITY_POWER", consumer, due).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS")).andExpect(jsonPath("$.billerRef").value(org.hamcrest.Matchers.startsWith("BBPS")))
                .andExpect(jsonPath("$.accountRefMasked").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(consumer))))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/internal/wallets/" + user + "/debit"))
                .withHeader("Idempotency-Key", equalTo(id + ":debit"))
                .withRequestBody(matchingJsonPath("$.purpose", equalTo("BILL_PAYMENT"))));

        pay(key, "4826", "CITY_POWER", consumer, due).andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id));
        pay(key, "4826", "CITY_POWER", consumer, "1.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'bill.payment-completed' AND aggregate_id = ?",
                Long.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT account_ref FROM bill_payments WHERE id = ?::uuid", String.class, id)).doesNotContain(consumer);
        mvc.perform(get("/api/v1/bills/history?category=ELECTRICITY").header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(user)))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/bills/{id}", id).header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void declinedBillIsRefunded() throws Exception {
        String body = pay(UUID.randomUUID().toString(), "4826", "VOLTEL_PREPAID", "9876543210", "199.13").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.failureCode").value("BILL_BILLER_DECLINED"))
                .andExpect(jsonPath("$.refunded").value(true)).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/internal/wallets/" + user + "/credit"))
                .withHeader("Idempotency-Key", equalTo(id + ":refund"))
                .withRequestBody(matchingJsonPath("$.purpose", equalTo("BILL_REFUND"))));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'bill.payment-failed' AND aggregate_id = ?",
                Long.class, id)).isEqualTo(1);
    }

    @Test
    void refusedDebitFailsWithoutRefundAndBillerTimeoutIsRetried() throws Exception {
        DOWNSTREAM.stubFor(WireMock.post("/internal/wallets/" + user + "/debit").atPriority(1)
                .withRequestBody(matchingJsonPath("$[?(@.amount == 500)]"))
                .willReturn(problem(422, "WALLET_INSUFFICIENT_BALANCE")));
        pay(UUID.randomUUID().toString(), "4826", "HIGHWAY_TAG", "ka01ab1234", "500").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.failureCode").value("WALLET_INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.refunded").value(false));

        String pending = pay(UUID.randomUUID().toString(), "4826", "STARVIEW_DTH", "1234512345", "300.14").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING")).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(pending, "$.id");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT status FROM bill_payments WHERE id = ?::uuid", String.class, id)).isEqualTo("SUCCESS"));
        DOWNSTREAM.verify(1, postRequestedFor(urlEqualTo("/internal/wallets/" + user + "/debit")).withHeader("Idempotency-Key", equalTo(id + ":debit")));
    }

    @Test
    void validationAndPin() throws Exception {
        pay(UUID.randomUUID().toString(), null, "VOLTEL_PREPAID", "9876543210", "100").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILL_PIN_REQUIRED"));
        pay(UUID.randomUUID().toString(), "1111", "VOLTEL_PREPAID", "9876543210", "100").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("AUTH_PIN_INCORRECT"));
        pay(UUID.randomUUID().toString(), "4826", "VOLTEL_PREPAID", "12345", "100").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILL_INVALID_ACCOUNT"));
        pay(UUID.randomUUID().toString(), "4826", "VOLTEL_PREPAID", "9876543210", "5000.01").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BILL_AMOUNT_OUT_OF_RANGE"));
        pay(UUID.randomUUID().toString(), "4826", "NOPE", "1", "1").andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/bills/fetch").header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"billerCode\":\"VOLTEL_PREPAID\",\"accountRef\":\"9876543210\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BILL_FETCH_NOT_SUPPORTED"));
        mvc.perform(post("/api/v1/bills/fetch").header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"billerCode\":\"CITY_POWER\",\"accountRef\":\"1234560000\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BILL_ACCOUNT_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bill_payments WHERE user_id = ?", Long.class, user)).isZero();
    }

    @Test
    void mandatesPayDueBillsOncePerRunAndSkipBillsAboveTheLimit() throws Exception {
        String recharge = mvc.perform(post("/api/v1/mandates").header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(user))
                        .header("X-Txn-Pin", "4826").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"billerCode\":\"VOLTEL_PREPAID\",\"accountRef\":\"9876543210\",\"frequency\":\"MONTHLY\",\"maxAmount\":300,\"amount\":239}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        String rechargeId = JsonPath.read(recharge, "$.id");
        String capped = mvc.perform(post("/api/v1/mandates").header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(user))
                        .header("X-Txn-Pin", "4826").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"billerCode\":\"CITY_POWER\",\"accountRef\":\"" + consumer + "\",\"frequency\":\"WEEKLY\",\"maxAmount\":1}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String cappedId = JsonPath.read(capped, "$.id");
        mvc.perform(post("/api/v1/mandates").header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(user)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"billerCode\":\"VOLTEL_PREPAID\",\"accountRef\":\"9876543210\",\"frequency\":\"MONTHLY\",\"maxAmount\":300,\"amount\":239}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BILL_PIN_REQUIRED"));
        mvc.perform(post("/api/v1/mandates").header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(user)).header("X-Txn-Pin", "4826")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"billerCode\":\"VOLTEL_PREPAID\",\"accountRef\":\"9876543210\",\"frequency\":\"MONTHLY\",\"maxAmount\":300}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BILL_INVALID_MANDATE"));

        mandates.runDue();
        mandates.runDue(); // not due any more: nothing happens twice

        mvc.perform(get("/api/v1/mandates/{id}/executions", rechargeId).header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(user)))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$[0].amount").value(239.0));
        mvc.perform(get("/api/v1/mandates/{id}/executions", cappedId).header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(user)))
                .andExpect(jsonPath("$[0].status").value("SKIPPED"))
                .andExpect(jsonPath("$[0].reason").value(org.hamcrest.Matchers.containsString("above the auto-pay limit")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bill_payments WHERE mandate_id = ?::uuid", Long.class, rechargeId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'bill.mandate-executed' AND aggregate_id IN (?, ?)",
                Long.class, rechargeId, cappedId)).isEqualTo(2);
        mvc.perform(get("/api/v1/mandates").header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(user)))
                .andExpect(jsonPath("$[?(@.id == '" + rechargeId + "')].runCount").value(1));

        mvc.perform(delete("/api/v1/mandates/{id}", rechargeId).header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/mandates/{id}", rechargeId).header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}
