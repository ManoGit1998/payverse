package com.payverse.rewards.client;

import com.payverse.common.money.Money;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * wallet-service internal API. Cashback is credited from the REWARDS_POOL account (purpose
 * CASHBACK) with Idempotency-Key {@code reward:<id>}; a clawback reverses that journal with
 * {@code reward:<id>:clawback}. Retries can never pay or claw back twice.
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

    public Posting creditCashback(UUID userId, Money amount, String idempotencyKey, UUID rewardId, String description) {
        return post("/internal/wallets/{userId}/credit", userId, idempotencyKey, Map.of("amount", amount.amount(), "purpose", "CASHBACK",
                "referenceType", "Reward", "referenceId", rewardId.toString(), "description", description));
    }

    public Posting reverse(UUID userId, UUID journalId, String idempotencyKey, String reason) {
        return post("/internal/wallets/{userId}/reverse", userId, idempotencyKey, Map.of("journalId", journalId, "reason", reason));
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
