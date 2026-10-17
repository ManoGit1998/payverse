package com.payverse.transaction.client;

import com.payverse.common.money.Money;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * wallet-service saga API. Every call carries a deterministic Idempotency-Key
 * ({@code <txnId>:debit|credit|compensate}), so retrying after a timeout can never move money twice.
 */
@Component
public class WalletClient {

    public record Posting(UUID journalId, UUID walletId, UUID userId, boolean replayed) {
    }

    private final RestClient restClient;
    private final CircuitBreakerFactory<?, ?> circuitBreakers;

    public WalletClient(@Qualifier("walletRestClient") RestClient restClient, CircuitBreakerFactory<?, ?> circuitBreakers) {
        this.restClient = restClient;
        this.circuitBreakers = circuitBreakers;
    }

    public Posting debit(UUID userId, Money amount, UUID transactionId, String description) {
        return post("/internal/wallets/{userId}/debit", userId, transactionId + ":debit", transferBody(amount, transactionId, description));
    }

    public Posting credit(UUID userId, Money amount, UUID transactionId, String description) {
        return post("/internal/wallets/{userId}/credit", userId, transactionId + ":credit", transferBody(amount, transactionId, description));
    }

    public Posting reverse(UUID userId, UUID journalId, UUID transactionId, String reason) {
        return post("/internal/wallets/{userId}/reverse", userId, transactionId + ":compensate",
                Map.of("journalId", journalId, "reason", reason == null ? "transfer failed" : reason));
    }

    private static Map<String, Object> transferBody(Money amount, UUID transactionId, String description) {
        return Map.of("amount", amount.amount(), "purpose", "TRANSFER", "referenceType", "Transaction",
                "referenceId", transactionId.toString(), "description", description == null ? "P2P transfer" : description);
    }

    private Posting post(String path, UUID userId, String key, Map<String, Object> body) {
        return circuitBreakers.create("wallet-service").run(() -> restClient.post().uri(path, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .body(body)
                .retrieve()
                .body(Posting.class), ClientFailures::rethrow);
    }
}
