package com.payverse.payment.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

/** user-service internal API (best effort: names are cosmetic, failures return empty). */
@Component
public class UserClient {

    record UserSummary(UUID userId, String fullName, String kycTier, String mobileMasked) {
    }

    private final RestClient restClient;
    private final CircuitBreakerFactory<?, ?> circuitBreakers;

    public UserClient(@Qualifier("userRestClient") RestClient restClient, CircuitBreakerFactory<?, ?> circuitBreakers) {
        this.restClient = restClient;
        this.circuitBreakers = circuitBreakers;
    }

    public Optional<String> displayName(UUID userId) {
        return circuitBreakers.create("user-service").run(() -> Optional.ofNullable(restClient.get()
                        .uri("/internal/users/{userId}", userId).retrieve().body(UserSummary.class))
                .map(UserSummary::fullName), failure -> Optional.empty());
    }
}
