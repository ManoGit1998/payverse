package com.payverse.bill.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param walletServiceUrl debits and refunds
 * @param authServiceUrl   PIN verification
 * @param staleAfter       a pending payment nobody touched for this long is resumed by the recovery job
 */
@ConfigurationProperties("payverse.bill")
public record BillProperties(String walletServiceUrl, String authServiceUrl, Duration staleAfter) {

    public BillProperties {
        walletServiceUrl = walletServiceUrl == null ? "http://localhost:8083" : walletServiceUrl;
        authServiceUrl = authServiceUrl == null ? "http://localhost:8081" : authServiceUrl;
        staleAfter = staleAfter == null ? Duration.ofSeconds(60) : staleAfter;
    }
}
