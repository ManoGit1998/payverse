package com.payverse.rewards.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * @param walletServiceUrl        wallet-service base URL (cashback credits and clawbacks)
 * @param scratchCardValidity     how long a scratch card can be scratched (default 30 days)
 * @param referrerBonus           paid to the referrer when the referee reaches MIN_KYC (default 100)
 * @param refereeBonus            paid to the referee at the same time (default 50)
 * @param maxCreditAttempts       wallet credit attempts before a reward is FAILED (default 8)
 * @param creditBatch             rewards credited per job run (default 50)
 */
@ConfigurationProperties("payverse.rewards")
public record RewardsProperties(String walletServiceUrl, Duration scratchCardValidity, BigDecimal referrerBonus,
                                BigDecimal refereeBonus, Integer maxCreditAttempts, Integer creditBatch) {

    public RewardsProperties {
        walletServiceUrl = walletServiceUrl == null ? "http://localhost:8083" : walletServiceUrl;
        scratchCardValidity = scratchCardValidity == null ? Duration.ofDays(30) : scratchCardValidity;
        referrerBonus = referrerBonus == null ? new BigDecimal("100.00") : referrerBonus;
        refereeBonus = refereeBonus == null ? new BigDecimal("50.00") : refereeBonus;
        maxCreditAttempts = maxCreditAttempts == null ? 8 : maxCreditAttempts;
        creditBatch = creditBatch == null ? 50 : creditBatch;
    }
}
