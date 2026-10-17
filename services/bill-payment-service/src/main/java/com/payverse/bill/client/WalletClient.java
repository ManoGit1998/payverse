package com.payverse.bill.client;

import com.payverse.common.money.Money;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * wallet-service: a bill payment debits the user into BILLER_SETTLEMENT ({@code <billId>:debit});
 * a declined bill is refunded from it ({@code <billId>:refund}). Retries replay.
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

    public Posting debit(UUID userId, Money amount, UUID billPaymentId, String description) {
        return post("/internal/wallets/{userId}/debit", userId, billPaymentId + ":debit", body(amount, "BILL_PAYMENT", billPaymentId, description));
    }

    public Posting refund(UUID userId, Money amount, UUID billPaymentId, String description) {
        return post("/internal/wallets/{userId}/credit", userId, billPaymentId + ":refund", body(amount, "BILL_REFUND", billPaymentId, description));
    }

    private static Map<String, Object> body(Money amount, String purpose, UUID billPaymentId, String description) {
        return Map.of("amount", amount.amount(), "purpose", purpose, "referenceType", "BillPayment",
                "referenceId", billPaymentId.toString(), "description", description);
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
