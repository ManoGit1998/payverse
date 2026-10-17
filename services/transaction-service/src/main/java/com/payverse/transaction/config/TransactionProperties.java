package com.payverse.transaction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * @param walletServiceUrl   saga steps (debit / credit / reverse)
 * @param authServiceUrl     PIN verification, mobile → user lookup
 * @param paymentServiceUrl  VPA resolution, QR decoding
 * @param userServiceUrl     display names (best effort)
 * @param staleAfter         an unfinished saga with no scheduled retry is picked up after this long (crash recovery)
 * @param maxSplitParticipants participants per split bill
 * @param fraudServiceUrl    pre-transfer risk assessment
 * @param riskFailOpenMax    if fraud-risk-service is unreachable, transfers up to this amount are allowed, larger ones blocked
 */
@ConfigurationProperties("payverse.transaction")
public record TransactionProperties(String walletServiceUrl, String authServiceUrl, String paymentServiceUrl, String userServiceUrl,
                                    Duration staleAfter, Integer maxSplitParticipants,
                                    String fraudServiceUrl, BigDecimal riskFailOpenMax) {

    public TransactionProperties {
        walletServiceUrl = walletServiceUrl == null ? "http://localhost:8083" : walletServiceUrl;
        authServiceUrl = authServiceUrl == null ? "http://localhost:8081" : authServiceUrl;
        paymentServiceUrl = paymentServiceUrl == null ? "http://localhost:8085" : paymentServiceUrl;
        userServiceUrl = userServiceUrl == null ? "http://localhost:8082" : userServiceUrl;
        staleAfter = staleAfter == null ? Duration.ofSeconds(60) : staleAfter;
        maxSplitParticipants = maxSplitParticipants == null ? 20 : maxSplitParticipants;
        fraudServiceUrl = fraudServiceUrl == null ? "http://localhost:8089" : fraudServiceUrl;
        riskFailOpenMax = riskFailOpenMax == null ? new BigDecimal("2000.00") : riskFailOpenMax;
    }
}
