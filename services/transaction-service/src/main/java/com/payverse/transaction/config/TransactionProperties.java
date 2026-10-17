package com.payverse.transaction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param walletServiceUrl   saga steps (debit / credit / reverse)
 * @param authServiceUrl     PIN verification, mobile → user lookup
 * @param paymentServiceUrl  VPA resolution, QR decoding
 * @param userServiceUrl     display names (best effort)
 * @param staleAfter         an unfinished saga with no scheduled retry is picked up after this long (crash recovery)
 * @param maxSplitParticipants participants per split bill
 */
@ConfigurationProperties("payverse.transaction")
public record TransactionProperties(String walletServiceUrl, String authServiceUrl, String paymentServiceUrl, String userServiceUrl,
                                    Duration staleAfter, Integer maxSplitParticipants) {

    public TransactionProperties {
        walletServiceUrl = walletServiceUrl == null ? "http://localhost:8083" : walletServiceUrl;
        authServiceUrl = authServiceUrl == null ? "http://localhost:8081" : authServiceUrl;
        paymentServiceUrl = paymentServiceUrl == null ? "http://localhost:8085" : paymentServiceUrl;
        userServiceUrl = userServiceUrl == null ? "http://localhost:8082" : userServiceUrl;
        staleAfter = staleAfter == null ? Duration.ofSeconds(60) : staleAfter;
        maxSplitParticipants = maxSplitParticipants == null ? 20 : maxSplitParticipants;
    }
}
