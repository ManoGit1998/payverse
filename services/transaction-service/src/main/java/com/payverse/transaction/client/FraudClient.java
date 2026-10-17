package com.payverse.transaction.client;

import com.payverse.transaction.entity.Transaction;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** fraud-risk-service {@code POST /internal/risk/assess} (idempotent per transaction). */
@Component
public class FraudClient {

    public record AssessRequest(UUID transactionId, String type, UUID userId, UUID payeeUserId, BigDecimal amount, String currency,
                                String deviceId) {
    }

    public record AssessResponse(UUID assessmentId, int score, String decision, List<String> reasons) {
    }

    private final RestClient restClient;
    private final CircuitBreakerFactory<?, ?> circuitBreakers;

    public FraudClient(@Qualifier("fraudRestClient") RestClient restClient, CircuitBreakerFactory<?, ?> circuitBreakers) {
        this.restClient = restClient;
        this.circuitBreakers = circuitBreakers;
    }

    public AssessResponse assess(Transaction t) {
        AssessRequest body = new AssessRequest(t.getId(), t.getType().name(), t.getPayerUserId(), t.getPayeeUserId(), t.getAmount(),
                t.getCurrency(), t.getDeviceId());
        return circuitBreakers.create("fraud-risk-service").run(() -> restClient.post().uri("/internal/risk/assess")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(AssessResponse.class), ClientFailures::rethrow);
    }
}
