package com.payverse.transaction.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** payment-service: UPI ID resolution and QR decoding (through its MOCK UPI switch). */
@Component
public class PaymentClient {

    public record ResolvedVpa(String vpa, UUID userId, String payeeName) {
    }

    public record MoneyValue(String amount, String currency) {
    }

    public record DecodedQr(UUID qrId, String vpa, UUID payeeUserId, String payeeName, MoneyValue amount, String note,
                            String type, Instant expiresAt) {
    }

    private final RestClient restClient;
    private final CircuitBreakerFactory<?, ?> circuitBreakers;

    public PaymentClient(@Qualifier("paymentRestClient") RestClient restClient, CircuitBreakerFactory<?, ?> circuitBreakers) {
        this.restClient = restClient;
        this.circuitBreakers = circuitBreakers;
    }

    public ResolvedVpa resolveVpa(String vpa) {
        return circuitBreakers.create("payment-service").run(() -> restClient.get().uri("/internal/vpa/{vpa}", vpa)
                .retrieve().body(ResolvedVpa.class), ClientFailures::rethrow);
    }

    public DecodedQr decodeQr(String payload) {
        return circuitBreakers.create("payment-service").run(() -> restClient.post().uri("/internal/qr/decode")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("payload", payload))
                .retrieve().body(DecodedQr.class), ClientFailures::rethrow);
    }
}
