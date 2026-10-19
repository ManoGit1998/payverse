package com.payverse.wallet;

import com.jayway.jsonpath.JsonPath;
import com.payverse.common.events.EventEnvelope;
import com.payverse.common.events.EventType;
import com.payverse.common.events.Topics;
import com.payverse.common.events.payload.AuthEvents;
import com.payverse.common.events.payload.UserEvents;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.payverse.common.money.Money;
import com.payverse.common.outbox.EventEnvelopeCodec;
import com.payverse.wallet.entity.KycTier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WalletApiIT extends WalletIntegrationTest {

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    EventEnvelopeCodec codec;

    ResultActions addMoney(UUID user, String key, String amount) throws Exception {
        return mvc.perform(post("/api/v1/wallets/me/add-money").header(HttpHeaders.AUTHORIZATION, bearer(user))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":" + amount + ",\"paymentMethod\":\"UPI\"}"));
    }

    ResultActions internal(String op, UUID user, String key, String body) throws Exception {
        return mvc.perform(post("/internal/wallets/" + user + "/" + op).header(HttpHeaders.AUTHORIZATION, serviceBearer("transaction-service"))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    static String transfer(String amount, String ref) {
        return "{\"amount\":" + amount + ",\"purpose\":\"TRANSFER\",\"referenceType\":\"Transaction\",\"referenceId\":\"" + ref + "\"}";
    }

    @Test
    void eventsCreateTheWalletAndSyncTheKycTier() throws Exception {
        UUID user = UUID.randomUUID();
        kafka.send(Topics.AUTH_USER_REGISTERED, user.toString(), codec.encode(EventEnvelope.create(EventType.USER_REGISTERED,
                "auth-service", user.toString(), null, new AuthEvents.UserRegistered(user, "******1234", null)))).get();
        await().atMost(Duration.ofSeconds(30)).until(() ->
                jdbc.queryForObject("SELECT count(*) FROM wallets WHERE user_id = ?", Integer.class, user) == 1);

        kafka.send(Topics.USER_KYC_UPGRADED, user.toString(), codec.encode(EventEnvelope.create(EventType.KYC_UPGRADED,
                "user-service", user.toString(), null, new UserEvents.KycUpgraded(user, "NONE", "MIN_KYC")))).get();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                mvc.perform(get("/api/v1/wallets/me").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                        .andExpect(jsonPath("$.kycTier").value("MIN_KYC"))
                        .andExpect(jsonPath("$.balance.amount").value("0.00"))
                        .andExpect(jsonPath("$.limits.maxBalance").value("10000.00")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'wallet.created' AND payload::text LIKE ?",
                Integer.class, "%" + user + "%")).isEqualTo(1);
    }

    @Test
    void withoutKycTheWalletCannotBeUsed() throws Exception {
        UUID user = UUID.randomUUID();
        mvc.perform(get("/api/v1/wallets/me").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycTier").value("NONE"));
        addMoney(user, UUID.randomUUID().toString(), "100")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WALLET_KYC_REQUIRED"));
    }

    @Test
    void addMoneyIsIdempotentAndLedgered() throws Exception {
        UUID user = fundedWallet(KycTier.MIN_KYC, "0");
        String key = UUID.randomUUID().toString();
        String first = addMoney(user, key, "500.50").andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance.amount").value("500.50"))
                .andExpect(jsonPath("$.paymentReference").value(org.hamcrest.Matchers.startsWith("MOCKPG-")))
                .andReturn().getResponse().getContentAsString();
        String replay = addMoney(user, key, "500.50").andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(replay, "$.journalId")).isEqualTo(JsonPath.read(first, "$.journalId"));
        assertThat(balance(user)).isEqualTo(Money.inr("500.50"));

        addMoney(user, key, "999").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(post("/api/v1/wallets/me/add-money").header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10,\"paymentMethod\":\"UPI\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_MISSING"));

        mvc.perform(get("/api/v1/wallets/me/ledger").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].type").value("ADD_MONEY"))
                .andExpect(jsonPath("$.items[0].side").value("CREDIT"))
                .andExpect(jsonPath("$.items[0].balanceAfter.amount").value("500.50"));
        await().atMost(Duration.ofSeconds(15)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE event_type IN ('wallet.money-added','wallet.ledger-posted') AND published_at IS NOT NULL AND payload::text LIKE ?",
                Integer.class, "%" + JsonPath.read(first, "$.journalId") + "%") == 2);

        String window = "from=" + java.time.Instant.now().minusSeconds(600) + "&to=" + java.time.Instant.now().plusSeconds(60);
        String journals = mvc.perform(get("/internal/ledger/journals?type=ADD_MONEY&size=1000&" + window)
                        .header(HttpHeaders.AUTHORIZATION, serviceBearer("reconciliation-service")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> refs = JsonPath.read(journals, "$.items[?(@.journalId == '" + JsonPath.read(first, "$.journalId") + "')].referenceId");
        assertThat(refs).containsExactly(JsonPath.<String>read(first, "$.paymentReference"));
        mvc.perform(get("/internal/ledger/journals?type=ADD_MONEY&" + window).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isForbidden());
    }

    @Test
    void limitsAndDeclinesPostNothing() throws Exception {
        UUID user = fundedWallet(KycTier.MIN_KYC, "9000");
        addMoney(user, UUID.randomUUID().toString(), "1000.01")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("WALLET_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.limit").value("MAX_BALANCE"))
                .andExpect(jsonPath("$.available").value("1000.00"));
        addMoney(user, UUID.randomUUID().toString(), "100.13")
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("WALLET_PAYMENT_DECLINED"));
        assertThat(balance(user)).isEqualTo(Money.inr("9000"));
    }

    @Test
    void withdrawVerifiesPinAndBankAccount() throws Exception {
        UUID user = fundedWallet(KycTier.MIN_KYC, "1000");
        UUID account = UUID.randomUUID();
        DOWNSTREAM.stubFor(WireMock.post("/internal/auth/pin/verify").withRequestBody(matchingJsonPath("$.pin", equalTo("4826")))
                .willReturn(okJson("{\"valid\":true}")));
        DOWNSTREAM.stubFor(WireMock.post("/internal/auth/pin/verify").withRequestBody(matchingJsonPath("$.pin", equalTo("1111")))
                .willReturn(aResponse().withStatus(422).withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"status\":422,\"title\":\"Incorrect transaction PIN\",\"code\":\"AUTH_PIN_INCORRECT\",\"remainingAttempts\":4}")));
        DOWNSTREAM.stubFor(WireMock.get("/internal/users/" + user + "/bank-accounts/" + account).willReturn(okJson(
                "{\"id\":\"" + account + "\",\"userId\":\"" + user + "\",\"maskedAccountNumber\":\"XXXXXXXX9012\",\"ifsc\":\"HDFC0001234\",\"holderName\":\"Asha\",\"primary\":true}")));

        String body = "{\"amount\":250.00,\"bankAccountId\":\"" + account + "\"}";
        mvc.perform(post("/api/v1/wallets/me/withdraw").header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WALLET_PIN_REQUIRED"));
        mvc.perform(post("/api/v1/wallets/me/withdraw").header(HttpHeaders.AUTHORIZATION, bearer(user)).header("X-Txn-Pin", "1111")
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("AUTH_PIN_INCORRECT"))
                .andExpect(jsonPath("$.remainingAttempts").value(4));

        mvc.perform(post("/api/v1/wallets/me/withdraw").header(HttpHeaders.AUTHORIZATION, bearer(user)).header("X-Txn-Pin", "4826")
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance.amount").value("750.00"))
                .andExpect(jsonPath("$.maskedAccountNumber").value("XXXXXXXX9012"))
                .andExpect(jsonPath("$.payoutReference").value(org.hamcrest.Matchers.startsWith("pout_")));
        DOWNSTREAM.verify(postRequestedFor(urlEqualTo("/internal/auth/pin/verify"))
                .withHeader(HttpHeaders.AUTHORIZATION, WireMock.matching("Bearer .+")));

        // payout rejected by the (mock) bank → the debit rolls back
        mvc.perform(post("/api/v1/wallets/me/withdraw").header(HttpHeaders.AUTHORIZATION, bearer(user)).header("X-Txn-Pin", "4826")
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.14,\"bankAccountId\":\"" + account + "\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("WALLET_PAYOUT_FAILED"));
        assertThat(balance(user)).isEqualTo(Money.inr("750"));
    }

    @Test
    void downstreamOutageIs503() throws Exception {
        UUID user = fundedWallet(KycTier.MIN_KYC, "100");
        DOWNSTREAM.stubFor(WireMock.post("/internal/auth/pin/verify").willReturn(aResponse().withStatus(500)));
        mvc.perform(post("/api/v1/wallets/me/withdraw").header(HttpHeaders.AUTHORIZATION, bearer(user)).header("X-Txn-Pin", "4826")
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10,\"bankAccountId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DOWNSTREAM_UNAVAILABLE"));
    }

    @Test
    void internalSagaStepsAreIdempotentAndCompensable() throws Exception {
        UUID payer = fundedWallet(KycTier.FULL_KYC, "1000");
        UUID payee = fundedWallet(KycTier.FULL_KYC, "0");
        String txn = UUID.randomUUID().toString();

        String debit = internal("debit", payer, txn + ":debit", transfer("300", txn)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance.amount").value("700.00"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn().getResponse().getContentAsString();
        internal("debit", payer, txn + ":debit", transfer("300", txn)).andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.balance.amount").value("700.00"));
        internal("credit", payee, txn + ":credit", transfer("300", txn)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance.amount").value("300.00"));

        // a second transfer whose credit fails is compensated
        String txn2 = UUID.randomUUID().toString();
        String debit2 = internal("debit", payer, txn2 + ":debit", transfer("100", txn2)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String journal2 = JsonPath.read(debit2, "$.journalId");
        internal("reverse", payer, txn2 + ":compensate", "{\"journalId\":\"" + journal2 + "\",\"reason\":\"credit failed\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.balance.amount").value("700.00"));
        internal("reverse", payer, txn2 + ":compensate-again", "{\"journalId\":\"" + journal2 + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));

        internal("debit", payer, UUID.randomUUID().toString(), transfer("5000", txn)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("WALLET_INSUFFICIENT_BALANCE"));
        internal("debit", payer, UUID.randomUUID().toString(), "{\"amount\":1,\"purpose\":\"CASHBACK\",\"referenceType\":\"x\",\"referenceId\":\"y\"}")
                .andExpect(status().isBadRequest());
        mvc.perform(post("/internal/wallets/" + payer + "/debit").header(HttpHeaders.AUTHORIZATION, bearer(payer))
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(transfer("1", txn)))
                .andExpect(status().isForbidden());

        assertThat(JsonPath.<String>read(debit, "$.journalId")).isNotBlank();
        mvc.perform(get("/internal/wallets/integrity").header(HttpHeaders.AUTHORIZATION, serviceBearer("reconciliation-service")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(true));
    }

    @Test
    void frozenWalletsRejectMovementsButAcceptCompensation() throws Exception {
        UUID user = fundedWallet(KycTier.FULL_KYC, "500");
        String txn = UUID.randomUUID().toString();
        String debit = internal("debit", user, txn + ":debit", transfer("100", txn)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mvc.perform(post("/internal/wallets/" + user + "/freeze").header(HttpHeaders.AUTHORIZATION, serviceBearer("admin-service"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"fraud review\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FROZEN"));
        internal("debit", user, UUID.randomUUID().toString(), transfer("10", txn))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("WALLET_FROZEN"));
        addMoney(user, UUID.randomUUID().toString(), "10").andExpect(status().isForbidden());
        internal("reverse", user, txn + ":compensate", "{\"journalId\":\"" + JsonPath.read(debit, "$.journalId") + "\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.balance.amount").value("500.00"));

        mvc.perform(post("/internal/wallets/" + user + "/unfreeze").header(HttpHeaders.AUTHORIZATION, serviceBearer("admin-service")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE entity_id = (SELECT id::text FROM wallets WHERE user_id = ?) ORDER BY created_at",
                String.class, user)).contains("WALLET_FROZEN", "WALLET_REVERSAL", "WALLET_UNFROZEN");
    }

    @Test
    void theLedgerIsAppendOnlyAndBalanced() {
        UUID user = fundedWallet(KycTier.MIN_KYC, "100");
        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount = 1 WHERE account_id = (SELECT ledger_account_id FROM wallets WHERE user_id = ?)", user))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM journals WHERE idempotency_key LIKE ?", "add-money:" + user + "%"))
                .hasMessageContaining("append-only");
        // a one-sided journal is rejected when the transaction commits
        UUID journalId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.execute("""
                BEGIN;
                INSERT INTO journals (id, type, idempotency_key, created_at) VALUES ('%s', 'ADD_MONEY', 'bad-%s', now());
                INSERT INTO ledger_entries (id, journal_id, account_id, side, amount, currency, created_at)
                     VALUES (gen_random_uuid(), '%s', '00000000-0000-0000-0000-000000000001', 'DEBIT', 10, 'INR', now());
                COMMIT;
                """.formatted(journalId, journalId, journalId))).hasMessageContaining("unbalanced");
        jdbc.execute("ROLLBACK");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journals WHERE id = ?", Integer.class, journalId)).isZero();
    }
}
