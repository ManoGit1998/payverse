package com.payverse.fraud.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/** wallet-service: freeze a wallet when fraud is confirmed (freezing twice is harmless). */
@Component
public class WalletClient {

    private final RestClient restClient;
    private final CircuitBreakerFactory<?, ?> circuitBreakers;

    public WalletClient(@Qualifier("walletRestClient") RestClient restClient, CircuitBreakerFactory<?, ?> circuitBreakers) {
        this.restClient = restClient;
        this.circuitBreakers = circuitBreakers;
    }

    public void freeze(UUID userId, String reason) {
        circuitBreakers.create("wallet-service").run(() -> restClient.post().uri("/internal/wallets/{userId}/freeze", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reason", reason))
                .retrieve()
                .toBodilessEntity(), ClientFailures::rethrow);
    }
}
