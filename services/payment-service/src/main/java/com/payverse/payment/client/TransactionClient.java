package com.payverse.payment.client;

import com.payverse.common.error.CommonErrorCode;
import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/** transaction-service internal transfer API (used when a payer approves a collect request). */
@Component
public class TransactionClient {

    public record TransferOutcome(UUID transactionId, String status, String failureCode, String failureReason) {
    }

    private final RestClient restClient;
    private final CircuitBreakerFactory<?, ?> circuitBreakers;

    public TransactionClient(@Qualifier("transactionRestClient") RestClient restClient, CircuitBreakerFactory<?, ?> circuitBreakers) {
        this.restClient = restClient;
        this.circuitBreakers = circuitBreakers;
    }

    /** Not retried by us: transaction-service is idempotent on the key, and the PIN check counts attempts. */
    public TransferOutcome transfer(UUID payerUserId, UUID payeeUserId, String payeeRef, Money amount, String note,
                                    String source, UUID sourceId, String pin, String idempotencyKey) {
        return circuitBreakers.create("transaction-service").run(() -> restClient.post().uri("/internal/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Txn-Pin", pin)
                .body(Map.of("payerUserId", payerUserId, "payeeUserId", payeeUserId, "payeeRef", payeeRef,
                        "amount", amount.amount(), "note", note == null ? "" : note, "source", source, "sourceId", sourceId))
                .retrieve()
                .body(TransferOutcome.class), failure -> {
            if (failure instanceof PayverseException payverse) {
                throw payverse;
            }
            throw new PayverseException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE, "transaction-service is unavailable", Map.of(), failure);
        });
    }
}
