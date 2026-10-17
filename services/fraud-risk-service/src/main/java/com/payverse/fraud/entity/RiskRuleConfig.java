package com.payverse.fraud.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Admin-tunable settings of one risk rule (the logic lives in a {@code RiskRule} implementation with the same code). */
@Entity
@Table(name = "risk_rules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RiskRuleConfig {

    public record Settings(int weight, boolean enabled, BigDecimal thresholdAmount, Integer thresholdCount, Integer windowSeconds,
                           BigDecimal multiplier) {
    }

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private int weight;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "threshold_amount")
    private BigDecimal thresholdAmount;

    @Column(name = "threshold_count")
    private Integer thresholdCount;

    @Column(name = "window_seconds")
    private Integer windowSeconds;

    private BigDecimal multiplier;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public static RiskRuleConfig create(String code, String description, Settings settings, Instant now) {
        RiskRuleConfig config = new RiskRuleConfig();
        config.id = UUID.randomUUID();
        config.code = code;
        config.description = description;
        config.update(settings, now);
        return config;
    }

    public void update(Settings s, Instant now) {
        if (s.weight() < 0 || s.weight() > 100) {
            throw new IllegalArgumentException("weight must be 0-100");
        }
        if ((s.thresholdAmount() != null && s.thresholdAmount().signum() < 0) || (s.thresholdCount() != null && s.thresholdCount() < 0)
                || (s.windowSeconds() != null && s.windowSeconds() <= 0) || (s.multiplier() != null && s.multiplier().signum() <= 0)) {
            throw new IllegalArgumentException("thresholds must not be negative, window and multiplier must be positive");
        }
        weight = s.weight();
        enabled = s.enabled();
        thresholdAmount = s.thresholdAmount();
        thresholdCount = s.thresholdCount();
        windowSeconds = s.windowSeconds();
        multiplier = s.multiplier();
        updatedAt = now;
    }

    public Duration window(Duration fallback) {
        return windowSeconds == null ? fallback : Duration.ofSeconds(windowSeconds);
    }
}
