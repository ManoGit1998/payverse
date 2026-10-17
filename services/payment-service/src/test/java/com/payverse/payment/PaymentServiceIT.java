package com.payverse.payment;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.jayway.jsonpath.JsonPath;
import com.payverse.common.events.Amount;
import com.payverse.common.events.EventEnvelope;
import com.payverse.common.events.EventType;
import com.payverse.common.events.Topics;
import com.payverse.common.events.payload.AuthEvents;
import com.payverse.common.events.payload.TransactionEvents;
import com.payverse.common.money.Money;
import com.payverse.common.outbox.EventEnvelopeCodec;
import com.payverse.common.security.PayverseJwtValidators;
import com.payverse.common.test.KafkaTestcontainer;
import com.payverse.common.test.PostgresTestcontainer;
import com.payverse.common.test.RedisTestcontainer;
import com.payverse.common.test.TestJwts;
import com.payverse.payment.entity.Vpa;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

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
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("it")
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, RedisTestcontainer.class, KafkaTestcontainer.class, PaymentServiceIT.TestTokens.class})
class PaymentServiceIT {

    static final WireMockServer DOWNSTREAM = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        DOWNSTREAM.start();
    }

    @DynamicPropertySource
    static void downstream(DynamicPropertyRegistry registry) {
        registry.add("payverse.payment.transaction-service-url", DOWNSTREAM::baseUrl);
        registry.add("payverse.payment.user-service-url", DOWNSTREAM::baseUrl);
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
    KafkaTemplate<String, String> kafka;

    @Autowired
    EventEnvelopeCodec codec;

    @BeforeEach
    void stubs() {
        DOWNSTREAM.resetAll();
        DOWNSTREAM.stubFor(WireMock.post("/api/v1/auth/oauth/token").willReturn(okJson(
                "{\"access_token\":\"" + TestJwts.INSTANCE.token("payment-service", List.of("SERVICE")) + "\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
        DOWNSTREAM.stubFor(WireMock.get(WireMock.urlMatching("/internal/users/.*")).willReturn(okJson(
                "{\"userId\":\"" + UUID.randomUUID() + "\",\"fullName\":\"Ravi Kumar\",\"kycTier\":\"MIN_KYC\",\"mobileMasked\":\"******3210\"}")));
    }

    static String bearer(UUID user) {
        return TestJwts.INSTANCE.bearer(user);
    }

    static String service() {
        return "Bearer " + TestJwts.INSTANCE.token("transaction-service", List.of("SERVICE"));
    }

    String defaultVpa(UUID user) throws Exception {
        String body = mvc.perform(get("/api/v1/vpa/me").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[0].vpa");
    }

    @Test
    void registrationCreatesTheDefaultVpa() throws Exception {
        UUID user = UUID.randomUUID();
        kafka.send(Topics.AUTH_USER_REGISTERED, user.toString(), codec.encode(EventEnvelope.create(EventType.USER_REGISTERED,
                "auth-service", user.toString(), null, new AuthEvents.UserRegistered(user, "******1111", null)))).get();
        await().atMost(Duration.ofSeconds(30)).until(() ->
                jdbc.queryForObject("SELECT count(*) FROM vpas WHERE user_id = ? AND is_default", Integer.class, user) == 1);
        assertThat(defaultVpa(user)).isEqualTo(Vpa.defaultHandleFor(user).value());
    }

    @Test
    void customVpasAreUniqueAndLimited() throws Exception {
        UUID user = UUID.randomUUID();
        String name = "asha" + Long.toString(System.nanoTime(), 36);
        mvc.perform(post("/api/v1/vpa").header(HttpHeaders.AUTHORIZATION, bearer(user)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vpa").value(name + "@payverse"))
                .andExpect(jsonPath("$.defaultVpa").value(false));
        mvc.perform(post("/api/v1/vpa").header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_VPA_TAKEN"));
        mvc.perform(post("/api/v1/vpa").header(HttpHeaders.AUTHORIZATION, bearer(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "x\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/vpa").header(HttpHeaders.AUTHORIZATION, bearer(user)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "y\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("PAYMENT_VPA_LIMIT"));
    }

    @Test
    void vpaResolutionGoesThroughTheMockSwitch() throws Exception {
        UUID payee = UUID.randomUUID();
        String vpa = defaultVpa(payee);
        mvc.perform(get("/api/v1/vpa/" + vpa + "/resolve").header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vpa").value(vpa))
                .andExpect(jsonPath("$.payeeName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.userId").doesNotExist());
        mvc.perform(get("/internal/vpa/" + vpa).header(HttpHeaders.AUTHORIZATION, service()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(payee.toString()));
        mvc.perform(get("/api/v1/vpa/ravi@okhdfc/resolve").header(HttpHeaders.AUTHORIZATION, bearer(payee)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("PAYMENT_VPA_EXTERNAL_UNSUPPORTED"));
        mvc.perform(get("/api/v1/vpa/nobody" + System.nanoTime() + "@payverse/resolve").header(HttpHeaders.AUTHORIZATION, bearer(payee)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PAYMENT_VPA_NOT_FOUND"));
        mvc.perform(get("/internal/vpa/" + vpa).header(HttpHeaders.AUTHORIZATION, bearer(payee))).andExpect(status().isForbidden());
    }

    @Test
    void qrCodesRenderAndDecode() throws Exception {
        UUID owner = UUID.randomUUID();
        String vpa = defaultVpa(owner);
        String staticQr = mvc.perform(post("/api/v1/qr/static").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("STATIC"))
                .andReturn().getResponse().getContentAsString();
        String again = mvc.perform(post("/api/v1/qr/static").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(again, "$.id")).isEqualTo(JsonPath.read(staticQr, "$.id"));

        String dynamic = mvc.perform(post("/api/v1/qr/dynamic").header(HttpHeaders.AUTHORIZATION, bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":149.50,\"note\":\"Coffee\",\"validityMinutes\":10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payload").value(org.hamcrest.Matchers.containsString("am=149.50")))
                .andReturn().getResponse().getContentAsString();
        String qrId = JsonPath.read(dynamic, "$.id");

        byte[] png = mvc.perform(get("/api/v1/qr/" + qrId + ".png").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andReturn().getResponse().getContentAsByteArray();
        mvc.perform(get("/api/v1/qr/" + qrId + ".png").header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID())))
                .andExpect(status().isForbidden());

        // scanning the rendered image yields the same payment
        mvc.perform(multipart("/api/v1/qr/decode-image").file(new MockMultipartFile("image", "qr.png", "image/png", png))
                        .header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qrId").value(qrId))
                .andExpect(jsonPath("$.vpa").value(vpa))
                .andExpect(jsonPath("$.amount.amount").value("149.50"))
                .andExpect(jsonPath("$.type").value("DYNAMIC"));
        mvc.perform(post("/internal/qr/decode").header(HttpHeaders.AUTHORIZATION, service()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":\"" + JsonPath.read(staticQr, "$.payload") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payeeUserId").value(owner.toString()))
                .andExpect(jsonPath("$.amount").doesNotExist());

        jdbc.update("UPDATE qr_codes SET expires_at = now() - interval '1 minute' WHERE id = ?::uuid", qrId);
        mvc.perform(post("/api/v1/qr/decode").header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":\"" + JsonPath.read(dynamic, "$.payload") + "\"}"))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("PAYMENT_QR_EXPIRED"));
        mvc.perform(post("/api/v1/qr/decode").header(HttpHeaders.AUTHORIZATION, bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":\"https://phish.example/pay\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAYMENT_INVALID_QR"));
    }

    @Test
    void collectApprovalMovesMoneyThroughTransactionService() throws Exception {
        UUID payer = UUID.randomUUID();
        UUID payee = UUID.randomUUID();
        String payerVpa = defaultVpa(payer);
        defaultVpa(payee);

        String created = mvc.perform(post("/api/v1/collect-requests").header(HttpHeaders.AUTHORIZATION, bearer(payee))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"payerVpa\":\"" + payerVpa + "\",\"amount\":300,\"note\":\"Dinner\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String collectId = JsonPath.read(created, "$.id");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'payment.collect-requested' AND aggregate_id = ?",
                Integer.class, collectId)).isEqualTo(1);
        mvc.perform(get("/api/v1/collect-requests?direction=INCOMING").header(HttpHeaders.AUTHORIZATION, bearer(payer)))
                .andExpect(jsonPath("$.items[0].id").value(collectId));

        mvc.perform(post("/api/v1/collect-requests/" + collectId + "/approve").header(HttpHeaders.AUTHORIZATION, bearer(payer))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAYMENT_PIN_REQUIRED"));
        // only the payer can approve
        mvc.perform(post("/api/v1/collect-requests/" + collectId + "/approve").header(HttpHeaders.AUTHORIZATION, bearer(payee))
                        .header("Idempotency-Key", UUID.randomUUID().toString()).header("X-Txn-Pin", "4826"))
                .andExpect(status().isNotFound());

        UUID txn = UUID.randomUUID();
        DOWNSTREAM.stubFor(WireMock.post("/internal/transfers").withHeader("Idempotency-Key", equalTo("collect:" + collectId))
                .withHeader("X-Txn-Pin", equalTo("4826"))
                .withRequestBody(matchingJsonPath("$.payerUserId", equalTo(payer.toString())))
                .willReturn(okJson("{\"transactionId\":\"" + txn + "\",\"status\":\"PENDING\"}")));
        mvc.perform(post("/api/v1/collect-requests/" + collectId + "/approve").header(HttpHeaders.AUTHORIZATION, bearer(payer))
                        .header("Idempotency-Key", UUID.randomUUID().toString()).header("X-Txn-Pin", "4826"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.transactionId").value(txn.toString()));
        DOWNSTREAM.verify(postRequestedFor(urlEqualTo("/internal/transfers")).withHeader(HttpHeaders.AUTHORIZATION, WireMock.matching("Bearer .+")));

        // the saga finishes later; its completion event settles the collect and records the UPI switch settlement
        var completed = new TransactionEvents.TransactionCompleted(txn, "P2P", payer, payee, Amount.of(Money.inr("300")), Instant.now());
        kafka.send(Topics.TRANSACTION_COMPLETED, txn.toString(), codec.encode(EventEnvelope.create(EventType.TRANSACTION_COMPLETED,
                "transaction-service", txn.toString(), null, completed))).get();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT status FROM collect_requests WHERE id = ?::uuid", String.class, collectId)).isEqualTo("APPROVED"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT rrn FROM upi_switch_records WHERE transaction_id = ?", String.class, txn)).hasSize(12));
        String window = "from=" + java.time.Instant.now().minusSeconds(600) + "&to=" + java.time.Instant.now().plusSeconds(60);
        String records = mvc.perform(get("/internal/upi/switch-records?size=1000&" + window).header(HttpHeaders.AUTHORIZATION, service()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<java.util.List<Object>>read(records, "$.items[?(@.transactionId == '" + txn + "')]")).hasSize(1);
    }

    @Test
    void failedTransfersKeepTheCollectPayable() throws Exception {
        UUID payer = UUID.randomUUID();
        UUID payee = UUID.randomUUID();
        String payerVpa = defaultVpa(payer);
        defaultVpa(payee);
        String collectId = JsonPath.read(mvc.perform(post("/api/v1/collect-requests").header(HttpHeaders.AUTHORIZATION, bearer(payee))
                .contentType(MediaType.APPLICATION_JSON).content("{\"payerVpa\":\"" + payerVpa + "\",\"amount\":50}"))
                .andReturn().getResponse().getContentAsString(), "$.id");

        DOWNSTREAM.stubFor(WireMock.post("/internal/transfers").willReturn(okJson("{\"transactionId\":\"" + UUID.randomUUID()
                + "\",\"status\":\"FAILED\",\"failureCode\":\"WALLET_INSUFFICIENT_BALANCE\",\"failureReason\":\"Insufficient wallet balance\"}")));
        mvc.perform(post("/api/v1/collect-requests/" + collectId + "/approve").header(HttpHeaders.AUTHORIZATION, bearer(payer))
                        .header("Idempotency-Key", UUID.randomUUID().toString()).header("X-Txn-Pin", "4826"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("WALLET_INSUFFICIENT_BALANCE"));
        assertThat(jdbc.queryForObject("SELECT status FROM collect_requests WHERE id = ?::uuid", String.class, collectId)).isEqualTo("PENDING");

        // a wrong PIN from transaction-service/auth-service passes straight through
        DOWNSTREAM.stubFor(WireMock.post("/internal/transfers").willReturn(aResponse().withStatus(422)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("{\"status\":422,\"title\":\"Incorrect transaction PIN\",\"code\":\"AUTH_PIN_INCORRECT\",\"remainingAttempts\":2}")));
        mvc.perform(post("/api/v1/collect-requests/" + collectId + "/approve").header(HttpHeaders.AUTHORIZATION, bearer(payer))
                        .header("Idempotency-Key", UUID.randomUUID().toString()).header("X-Txn-Pin", "1111"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("AUTH_PIN_INCORRECT"))
                .andExpect(jsonPath("$.remainingAttempts").value(2));

        mvc.perform(post("/api/v1/collect-requests/" + collectId + "/reject").header(HttpHeaders.AUTHORIZATION, bearer(payer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        mvc.perform(post("/api/v1/collect-requests").header(HttpHeaders.AUTHORIZATION, bearer(payee)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payerVpa\":\"" + defaultVpa(payee) + "\",\"amount\":5}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("PAYMENT_SELF_COLLECT"));
    }

    @Test
    void gatewayChargesAreIdempotentAndPublished() throws Exception {
        UUID user = UUID.randomUUID();
        String key = "add-money:" + user + ":" + UUID.randomUUID();
        String body = "{\"userId\":\"" + user + "\",\"amount\":500,\"method\":\"UPI\",\"idempotencyKey\":\"" + key + "\"}";
        String first = mvc.perform(post("/internal/pg/charges").header(HttpHeaders.AUTHORIZATION, service()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.providerReference").value(org.hamcrest.Matchers.startsWith("MOCKPG-")))
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/internal/pg/charges").header(HttpHeaders.AUTHORIZATION, service()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(second, "$.orderId")).isEqualTo(JsonPath.read(first, "$.orderId"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'payment.pg-charge-settled' AND aggregate_id = ?",
                Integer.class, JsonPath.<String>read(first, "$.orderId"))).isEqualTo(1);

        mvc.perform(post("/internal/pg/charges").header(HttpHeaders.AUTHORIZATION, service()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + user + "\",\"amount\":100.13,\"method\":\"CARD\",\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.failureReason").value(org.hamcrest.Matchers.containsString(".13")));

        String window = "from=" + java.time.Instant.now().minusSeconds(600) + "&to=" + java.time.Instant.now().plusSeconds(60);
        String charges = mvc.perform(get("/internal/pg/charges?size=1000&" + window).header(HttpHeaders.AUTHORIZATION, service()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<java.util.List<String>>read(charges, "$.items[?(@.orderId == '" + JsonPath.read(first, "$.orderId") + "')].providerReference"))
                .containsExactly(JsonPath.<String>read(first, "$.providerReference"));
        String declined = mvc.perform(get("/internal/pg/charges?status=DECLINED&size=1000&" + window).header(HttpHeaders.AUTHORIZATION, service()))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<java.util.List<String>>read(declined, "$.items[*].orderId")).doesNotContain(JsonPath.<String>read(first, "$.orderId"));
    }
}
