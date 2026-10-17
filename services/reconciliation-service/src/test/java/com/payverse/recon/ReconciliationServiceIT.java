package com.payverse.recon;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.jayway.jsonpath.JsonPath;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real Postgres (incl. Spring Batch metadata), Redis, Kafka; WireMock plays wallet, payment and transaction feeds. */
@SpringBootTest
@ActiveProfiles("it")
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, RedisTestcontainer.class, KafkaTestcontainer.class, ReconciliationServiceIT.TestTokens.class})
class ReconciliationServiceIT {

    static final WireMockServer FEEDS = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        FEEDS.start();
    }

    @DynamicPropertySource
    static void downstream(DynamicPropertyRegistry registry) {
        for (String svc : List.of("wallet", "payment", "transaction")) {
            registry.add("payverse.recon." + svc + "-service-url", FEEDS::baseUrl);
        }
        registry.add("payverse.service-client.token-uri", () -> FEEDS.baseUrl() + "/api/v1/auth/oauth/token");
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
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    LocalDate day;

    @BeforeEach
    void stubs() {
        // a random past day per test, so runs and references never collide across reused containers
        day = LocalDate.of(2020, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(2000));
        FEEDS.resetAll();
        FEEDS.stubFor(WireMock.post("/api/v1/auth/oauth/token").willReturn(okJson(
                "{\"access_token\":\"" + TestJwts.INSTANCE.token("reconciliation-service", List.of("SERVICE")) + "\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
    }

    String at(int hour, int minute) {
        return day.atTime(hour, minute).atZone(IST).toInstant().toString();
    }

    static String page(String items, int page, int totalPages) {
        return "{\"items\":[" + items + "],\"page\":" + page + ",\"size\":500,\"totalItems\":0,\"totalPages\":" + totalPages + "}";
    }

    String run(String job) throws Exception {
        String body = mvc.perform(post("/api/v1/admin/recon/jobs/{job}/run?date=" + day, job).header(HttpHeaders.AUTHORIZATION, ADMIN))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("RUNNING"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        await().atMost(Duration.ofSeconds(30)).until(() ->
                !"RUNNING".equals(jdbc.queryForObject("SELECT status FROM recon_runs WHERE id = ?::uuid", String.class, id)));
        return id;
    }

    @Test
    void pgChargesVsLedgerFindsInjectedMismatches() throws Exception {
        String p = "P" + UUID.randomUUID().toString().substring(0, 8);
        FEEDS.stubFor(WireMock.get(urlPathEqualTo("/internal/pg/charges")).willReturn(okJson(page(String.join(",",
                charge(p + "-1", "100.00", at(10, 0)), charge(p + "-2", "200.00", at(11, 0)),
                charge(p + "-3", "300.00", at(12, 0)),   // charged, never credited
                charge(p + "-4", "50.00", at(23, 59))),  // credited with a different amount, just after midnight
                0, 1))));
        FEEDS.stubFor(WireMock.get(urlPathEqualTo("/internal/ledger/journals")).withQueryParam("type", equalTo("ADD_MONEY"))
                .willReturn(okJson(page(String.join(",",
                        journal(p + "-1", "100.00", at(10, 0)), journal(p + "-2", "200.00", at(11, 0)),
                        journal(p + "-4", "55.00", day.plusDays(1).atTime(0, 1).atZone(IST).toInstant().toString()),
                        journal(p + "-5", "70.00", at(15, 0)),                                                      // no charge
                        journal(p + "-6", "80.00", day.plusDays(1).atTime(0, 5).atZone(IST).toInstant().toString())), // next day's
                        0, 1))));

        String runId = run("PG_VS_LEDGER");
        mvc.perform(get("/api/v1/admin/recon/runs/{id}", runId).header(HttpHeaders.AUTHORIZATION, SUPPORT))
                .andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.leftCount").value(4))
                .andExpect(jsonPath("$.rightCount").value(3)).andExpect(jsonPath("$.matchedCount").value(2))
                .andExpect(jsonPath("$.mismatchCount").value(3)).andExpect(jsonPath("$.jobExecutionId").isNumber());
        mvc.perform(get("/api/v1/admin/recon/runs/{id}/mismatches", runId).header(HttpHeaders.AUTHORIZATION, SUPPORT))
                .andExpect(jsonPath("$.items[*].type", containsInAnyOrder("AMOUNT_MISMATCH", "CHARGED_NOT_CREDITED", "CREDITED_WITHOUT_CHARGE")))
                .andExpect(jsonPath("$.items[?(@.type == 'AMOUNT_MISMATCH')].reference").value(p + "-4"))
                .andExpect(jsonPath("$.items[?(@.type == 'CHARGED_NOT_CREDITED')].expectedAmount").value(300.0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'recon.mismatch-detected' AND aggregate_id = ?",
                Long.class, runId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT e.status FROM batch_job_execution e JOIN recon_runs r ON r.job_execution_id = e.job_execution_id "
                + "WHERE r.id = ?::uuid", String.class, runId)).isEqualTo("COMPLETED");

        String mismatch = jdbc.queryForObject("SELECT id::text FROM recon_mismatches WHERE run_id = ?::uuid AND type = 'CHARGED_NOT_CREDITED'",
                String.class, runId);
        mvc.perform(post("/api/v1/admin/recon/mismatches/{id}/resolve", mismatch).header(HttpHeaders.AUTHORIZATION, SUPPORT)
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"refunded\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/recon/mismatches/{id}/resolve", mismatch).header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Refunded to the card via the PG\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED")).andExpect(jsonPath("$.resolvedBy").value("admin-1"));
        mvc.perform(post("/api/v1/admin/recon/mismatches/{id}/resolve", mismatch).header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"again\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECON_MISMATCH_ALREADY_RESOLVED"));
        String open = mvc.perform(get("/api/v1/admin/recon/mismatches?size=500").header(HttpHeaders.AUTHORIZATION, SUPPORT))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(open, "$.items[*].id")).doesNotContain(mismatch);
    }

    @Test
    void transfersVsUpiReadsAllPages() throws Exception {
        String t1 = UUID.randomUUID().toString();
        String t2 = UUID.randomUUID().toString();
        String t3 = UUID.randomUUID().toString();
        FEEDS.stubFor(WireMock.get(urlPathEqualTo("/internal/transactions")).withQueryParam("page", equalTo("0"))
                .willReturn(okJson(page(txn(t1, "10.00", at(9, 0)) + "," + txn(t2, "20.00", at(9, 30)), 0, 2))));
        FEEDS.stubFor(WireMock.get(urlPathEqualTo("/internal/transactions")).withQueryParam("page", equalTo("1"))
                .willReturn(okJson(page(txn(t3, "30.00", at(10, 0)), 1, 2))));
        FEEDS.stubFor(WireMock.get(urlPathEqualTo("/internal/upi/switch-records")).willReturn(okJson(page(
                record(t1, "10.00", at(9, 0)) + "," + record(t3, "30.00", at(10, 0)), 0, 1))));

        String runId = run("TRANSFERS_VS_UPI");
        mvc.perform(get("/api/v1/admin/recon/runs/{id}/mismatches", runId).header(HttpHeaders.AUTHORIZATION, SUPPORT))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].type").value("MISSING_SWITCH_RECORD"))
                .andExpect(jsonPath("$.items[0].reference").value(t2));
        mvc.perform(get("/api/v1/admin/recon/runs?job=TRANSFERS_VS_UPI").header(HttpHeaders.AUTHORIZATION, SUPPORT))
                .andExpect(jsonPath("$.items[0].leftCount").isNumber());
    }

    @Test
    void walletIntegrityAndFeedFailures() throws Exception {
        String wallet = UUID.randomUUID().toString();
        FEEDS.stubFor(WireMock.get("/internal/wallets/integrity").willReturn(okJson("{\"healthy\":false,\"walletsOutOfBalance\":[\"" + wallet
                + "\"],\"unbalancedJournals\":[],\"transferSuspense\":{\"amount\":\"0.00\",\"currency\":\"INR\"},\"checkedAt\":\"" + Instant.now() + "\"}")));
        String runId = run("WALLET_INTEGRITY");
        mvc.perform(get("/api/v1/admin/recon/runs/{id}/mismatches", runId).header(HttpHeaders.AUTHORIZATION, SUPPORT))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].type").value("WALLET_OUT_OF_BALANCE")).andExpect(jsonPath("$.items[0].reference").value(wallet));

        FEEDS.stubFor(WireMock.get(urlPathEqualTo("/internal/pg/charges")).willReturn(aResponse().withStatus(500)));
        String failed = run("PG_VS_LEDGER");
        mvc.perform(get("/api/v1/admin/recon/runs/{id}", failed).header(HttpHeaders.AUTHORIZATION, SUPPORT))
                .andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.error").isNotEmpty());

        mvc.perform(post("/api/v1/admin/recon/jobs/PG_VS_LEDGER/run").header(HttpHeaders.AUTHORIZATION, SUPPORT)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/recon/runs/{id}", UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, SUPPORT)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/recon/runs").header(HttpHeaders.AUTHORIZATION, TestJwts.INSTANCE.bearer(UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    static String charge(String ref, String amount, String at) {
        return "{\"orderId\":\"" + UUID.randomUUID() + "\",\"userId\":\"" + UUID.randomUUID() + "\",\"amount\":" + amount
                + ",\"providerReference\":\"" + ref + "\",\"status\":\"SUCCEEDED\",\"createdAt\":\"" + at + "\"}";
    }

    static String journal(String ref, String amount, String at) {
        return "{\"journalId\":\"" + UUID.randomUUID() + "\",\"type\":\"ADD_MONEY\",\"referenceType\":\"PgCharge\",\"referenceId\":\"" + ref
                + "\",\"amount\":" + amount + ",\"createdAt\":\"" + at + "\"}";
    }

    static String txn(String id, String amount, String at) {
        return "{\"id\":\"" + id + "\",\"status\":\"SUCCESS\",\"amount\":" + amount + ",\"completedAt\":\"" + at + "\"}";
    }

    static String record(String txn, String amount, String at) {
        return "{\"rrn\":\"" + ThreadLocalRandom.current().nextLong(100000000000L, 999999999999L) + "\",\"transactionId\":\"" + txn
                + "\",\"amount\":" + amount + ",\"status\":\"SUCCESS\",\"createdAt\":\"" + at + "\"}";
    }
}
