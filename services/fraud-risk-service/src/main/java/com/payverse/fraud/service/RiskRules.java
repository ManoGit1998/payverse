package com.payverse.fraud.service;

import com.payverse.fraud.entity.RiskRuleConfig;
import com.payverse.fraud.model.RiskContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.Duration;

/** The built-in rules. Missing parameters fall back to safe defaults. */
@Configuration(proxyBeanMethods = false)
public class RiskRules {

    /** More than {@code thresholdCount} transfers within the window (default 5 in 10 minutes). */
    @Bean
    RiskRule velocityCount() {
        return rule("VELOCITY_COUNT", (c, cfg) -> c.velocity().apply(cfg.window(Duration.ofMinutes(10))).count() > orDefault(cfg.getThresholdCount(), 5));
    }

    /** More than {@code thresholdAmount} sent within the window (default 50,000 in 24 hours). */
    @Bean
    RiskRule velocityAmount() {
        return rule("VELOCITY_AMOUNT", (c, cfg) -> c.velocity().apply(cfg.window(Duration.ofHours(24))).total()
                .compareTo(orDefault(cfg.getThresholdAmount(), new BigDecimal("50000"))) > 0);
    }

    /** At least {@code thresholdAmount} and more than {@code multiplier} × the user's average, once there is enough history. */
    @Bean
    RiskRule amountAnomaly() {
        return rule("AMOUNT_ANOMALY", (c, cfg) -> c.completedCount() >= orDefault(cfg.getThresholdCount(), 3)
                && c.amount().compareTo(orDefault(cfg.getThresholdAmount(), new BigDecimal("1000"))) >= 0
                && c.amount().compareTo(c.averageAmount().multiply(orDefault(cfg.getMultiplier(), new BigDecimal("5")))) > 0);
    }

    /** At least {@code thresholdAmount} from a device that first logged in to this account within the window. */
    @Bean
    RiskRule newDevice() {
        return rule("NEW_DEVICE", (c, cfg) -> c.deviceFirstSeen().isPresent()
                && c.deviceFirstSeen().get().isAfter(c.now().minus(cfg.window(Duration.ofHours(24))))
                && c.amount().compareTo(orDefault(cfg.getThresholdAmount(), new BigDecimal("2000"))) >= 0);
    }

    /** The PIN was locked (too many wrong attempts) within the window. */
    @Bean
    RiskRule recentPinLock() {
        return rule("RECENT_PIN_LOCK", (c, cfg) -> c.lastPinLockAt().isPresent()
                && c.lastPinLockAt().get().isAfter(c.now().minus(cfg.window(Duration.ofHours(24)))));
    }

    /** A single transfer of at least {@code thresholdAmount}. */
    @Bean
    RiskRule largeAmount() {
        return rule("LARGE_AMOUNT", (c, cfg) -> c.amount().compareTo(orDefault(cfg.getThresholdAmount(), new BigDecimal("50000"))) >= 0);
    }

    interface Check {
        boolean test(RiskContext context, RiskRuleConfig config);
    }

    static RiskRule rule(String code, Check check) {
        return new RiskRule() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public boolean triggered(RiskContext context, RiskRuleConfig config) {
                return check.test(context, config);
            }

            @Override
            public String toString() {
                return "RiskRule[" + code + "]";
            }
        };
    }

    private static <T> T orDefault(T value, T fallback) {
        return value == null ? fallback : value;
    }
}
