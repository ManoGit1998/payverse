package com.payverse.wallet.client;

import com.payverse.common.money.Money;
import com.payverse.wallet.service.PaymentGateway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * Charges through payment-service ({@code POST /internal/pg/charges}), which owns the payment-gateway
 * integration (MOCK gateway: amounts ending in .13 are declined). The call is idempotent per key, so
 * a retried add-money never charges twice.
 */
@Component
public class PaymentServiceGateway implements PaymentGateway {

    record ChargeResponse(UUID orderId, String status, String providerReference, String failureReason) {
    }

    private final RestClient restClient;
    private final CircuitBreakerFactory<?, ?> circuitBreakers;

    public PaymentServiceGateway(@Qualifier("paymentRestClient") RestClient restClient, CircuitBreakerFactory<?, ?> circuitBreakers) {
        this.restClient = restClient;
        this.circuitBreakers = circuitBreakers;
    }

    @Override
    public ChargeResult charge(UUID userId, Money amount, String method, String idempotencyKey) {
        ChargeResponse response = circuitBreakers.create("payment-service").run(() -> restClient.post().uri("/internal/pg/charges")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("userId", userId, "amount", amount.amount(), "method", method,
                        "idempotencyKey", "add-money:" + userId + ":" + idempotencyKey))
                .retrieve()
                .body(ChargeResponse.class), ClientFailures::rethrow);
        return "SUCCEEDED".equals(response.status())
                ? new ChargeResult.Succeeded(response.providerReference())
                : new ChargeResult.Declined(response.failureReason());
    }
}
