package com.payverse.wallet.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/** user-service internal API. */
@Component
public class UserClient {

    public record BankAccount(UUID id, UUID userId, String maskedAccountNumber, String ifsc, String holderName, boolean primary) {
    }

    private final RestClient restClient;
    private final CircuitBreakerFactory<?, ?> circuitBreakers;

    public UserClient(@Qualifier("userRestClient") RestClient restClient, CircuitBreakerFactory<?, ?> circuitBreakers) {
        this.restClient = restClient;
        this.circuitBreakers = circuitBreakers;
    }

    /** The user's linked bank account; USER_BANK_ACCOUNT_NOT_FOUND propagates as a 404. */
    public BankAccount bankAccount(UUID userId, UUID accountId) {
        return circuitBreakers.create("user-service").run(() -> restClient.get()
                .uri("/internal/users/{userId}/bank-accounts/{accountId}", userId, accountId)
                .retrieve()
                .body(BankAccount.class), ClientFailures::rethrow);
    }
}
