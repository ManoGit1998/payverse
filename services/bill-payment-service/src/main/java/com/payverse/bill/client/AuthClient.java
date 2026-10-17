package com.payverse.bill.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/** auth-service PIN verification (never retried — attempts count toward the lockout). */
@Component
public class AuthClient {

    private final RestClient restClient;
    private final CircuitBreakerFactory<?, ?> circuitBreakers;

    public AuthClient(@Qualifier("authRestClient") RestClient restClient, CircuitBreakerFactory<?, ?> circuitBreakers) {
        this.restClient = restClient;
        this.circuitBreakers = circuitBreakers;
    }

    public void verifyPin(UUID userId, String pin) {
        circuitBreakers.create("auth-service").run(() -> {
            restClient.post().uri("/internal/auth/pin/verify").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("userId", userId, "pin", pin)).retrieve().toBodilessEntity();
            return null;
        }, ClientFailures::rethrow);
    }
}
