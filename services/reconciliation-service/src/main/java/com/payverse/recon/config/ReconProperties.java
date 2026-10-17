package com.payverse.recon.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param tolerance the RIGHT side is read this much beyond the business day on both ends, so a
 *                  record posted a few seconds after midnight still matches (default 15 minutes)
 */
@ConfigurationProperties("payverse.recon")
public record ReconProperties(String walletServiceUrl, String paymentServiceUrl, String transactionServiceUrl, Duration tolerance,
                              Integer chunkSize) {

    public ReconProperties {
        walletServiceUrl = walletServiceUrl == null ? "http://localhost:8083" : walletServiceUrl;
        paymentServiceUrl = paymentServiceUrl == null ? "http://localhost:8085" : paymentServiceUrl;
        transactionServiceUrl = transactionServiceUrl == null ? "http://localhost:8084" : transactionServiceUrl;
        tolerance = tolerance == null ? Duration.ofMinutes(15) : tolerance;
        chunkSize = chunkSize == null ? 200 : chunkSize;
    }
}
