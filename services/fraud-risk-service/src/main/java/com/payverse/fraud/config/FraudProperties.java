package com.payverse.fraud.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param reviewScore      score from which a transfer is flagged for review (still allowed), default 50
 * @param blockScore       score from which a transfer is blocked, default 80
 * @param walletServiceUrl wallet-service, to freeze a wallet when fraud is confirmed
 */
@ConfigurationProperties("payverse.fraud")
public record FraudProperties(Integer reviewScore, Integer blockScore, String walletServiceUrl) {

    public FraudProperties {
        reviewScore = reviewScore == null ? 50 : reviewScore;
        blockScore = blockScore == null ? 80 : blockScore;
        walletServiceUrl = walletServiceUrl == null ? "http://localhost:8083" : walletServiceUrl;
        if (reviewScore > blockScore) {
            throw new IllegalArgumentException("review-score must not exceed block-score");
        }
    }
}
