package com.payverse.wallet.config;

import com.payverse.wallet.entity.KycTier;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * Wallet policy. Limits per KYC tier (INR):
 * <pre>
 *            max balance   monthly load (add money)   daily debit
 * NONE              0                0                       0      (KYC required)
 * MIN_KYC      10,000           10,000                  10,000
 * FULL_KYC    200,000          200,000                 100,000
 * </pre>
 *
 * @param limits               per-tier limits (defaults above)
 * @param maxTransactionAmount ceiling for any single posting
 * @param lockWait             how long to wait for a busy wallet before failing with WALLET_LOCK_TIMEOUT
 * @param lockLease            safety expiry of the Redis wallet lock
 * @param summaryCacheTtl      TTL of the cached wallet summary
 * @param authServiceUrl       base URL of auth-service (PIN verification)
 * @param userServiceUrl       base URL of user-service (bank accounts)
 * @param paymentServiceUrl    base URL of payment-service (payment-gateway charges)
 */
@ConfigurationProperties("payverse.wallet")
public record WalletProperties(Map<KycTier, TierLimits> limits, BigDecimal maxTransactionAmount, Duration lockWait,
                               Duration lockLease, Duration summaryCacheTtl, String authServiceUrl, String userServiceUrl,
                               String paymentServiceUrl) {

    public record TierLimits(BigDecimal maxBalance, BigDecimal monthlyLoad, BigDecimal dailyDebit) {
    }

    public WalletProperties {
        Map<KycTier, TierLimits> merged = new EnumMap<>(KycTier.class);
        merged.put(KycTier.NONE, new TierLimits(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        merged.put(KycTier.MIN_KYC, new TierLimits(new BigDecimal("10000"), new BigDecimal("10000"), new BigDecimal("10000")));
        merged.put(KycTier.FULL_KYC, new TierLimits(new BigDecimal("200000"), new BigDecimal("200000"), new BigDecimal("100000")));
        if (limits != null) {
            merged.putAll(limits);
        }
        limits = Map.copyOf(merged);
        maxTransactionAmount = maxTransactionAmount == null ? new BigDecimal("100000") : maxTransactionAmount;
        lockWait = lockWait == null ? Duration.ofSeconds(10) : lockWait;
        lockLease = lockLease == null ? Duration.ofSeconds(30) : lockLease;
        summaryCacheTtl = summaryCacheTtl == null ? Duration.ofSeconds(60) : summaryCacheTtl;
        authServiceUrl = authServiceUrl == null ? "http://localhost:8081" : authServiceUrl;
        userServiceUrl = userServiceUrl == null ? "http://localhost:8082" : userServiceUrl;
        paymentServiceUrl = paymentServiceUrl == null ? "http://localhost:8085" : paymentServiceUrl;
    }

    public TierLimits limitsFor(KycTier tier) {
        return limits.get(tier);
    }
}
