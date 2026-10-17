package com.payverse.transaction.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

/** user-service: names and masked mobiles for display (best effort — never blocks a transfer). */
@Component
public class UserClient {

    public record UserSummary(UUID userId, String fullName, String kycTier, String mobileMasked) {

        /** "Asha Verma (******3210)", or whichever part is known. */
        public String display() {
            if (fullName != null && mobileMasked != null) {
                return fullName + " (" + mobileMasked + ")";
            }
            return fullName != null ? fullName : mobileMasked;
        }
    }

    private final RestClient restClient;
    private final CircuitBreakerFactory<?, ?> circuitBreakers;

    public UserClient(@Qualifier("userRestClient") RestClient restClient, CircuitBreakerFactory<?, ?> circuitBreakers) {
        this.restClient = restClient;
        this.circuitBreakers = circuitBreakers;
    }

    public Optional<UserSummary> summary(UUID userId) {
        return circuitBreakers.create("user-service").run(() -> Optional.ofNullable(restClient.get()
                .uri("/internal/users/{userId}", userId).retrieve().body(UserSummary.class)), failure -> Optional.empty());
    }
}
