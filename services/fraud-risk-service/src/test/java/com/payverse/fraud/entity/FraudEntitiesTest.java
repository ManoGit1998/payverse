package com.payverse.fraud.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudEntitiesTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    static RiskAssessment assessment(Decision decision, int score) {
        return RiskAssessment.record(UUID.randomUUID(), UUID.randomUUID(), null, "P2P", new BigDecimal("10"), "INR", "d",
                score, decision, List.of("A", "B"), "rules-v1", NOW);
    }

    @Test
    void assessmentClampsScoreAndSplitsReasons() {
        assertThat(assessment(Decision.BLOCK, 140).getScore()).isEqualTo(100);
        assertThat(assessment(Decision.ALLOW, -5).getScore()).isZero();
        assertThat(assessment(Decision.ALLOW, 0).reasonList()).containsExactly("A", "B");
        assertThat(RiskAssessment.record(UUID.randomUUID(), UUID.randomUUID(), null, "P2P", BigDecimal.ONE, "INR", null, 0,
                Decision.ALLOW, List.of(), "v", NOW).reasonList()).isEmpty();
    }

    @Test
    void flagLifecycle() {
        assertThatThrownBy(() -> Flag.raise(assessment(Decision.ALLOW, 0), NOW)).isInstanceOf(IllegalArgumentException.class);
        Flag flag = Flag.raise(assessment(Decision.REVIEW, 60), NOW);
        assertThat(flag.isOpen()).isTrue();
        assertThat(flag.getReasons()).isEqualTo("A,B");
        assertThatThrownBy(() -> flag.resolve(FlagStatus.OPEN, "a", null, false, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> flag.resolve(FlagStatus.FALSE_POSITIVE, "a", null, true, NOW)).isInstanceOf(IllegalArgumentException.class);
        flag.resolve(FlagStatus.CONFIRMED_FRAUD, "admin", "x".repeat(600), true, NOW);
        assertThat(flag.getResolutionNote()).hasSize(500);
        assertThat(flag.isWalletFrozen()).isTrue();
        assertThat(flag.getResolvedBy()).isEqualTo("admin");
        assertThatThrownBy(() -> flag.resolve(FlagStatus.FALSE_POSITIVE, "a", null, false, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ruleSettingsAreValidated() {
        RiskRuleConfig config = RiskRuleConfig.create("X", "d", new RiskRuleConfig.Settings(10, true, null, null, 60, null), NOW);
        assertThat(config.window(Duration.ofHours(1))).isEqualTo(Duration.ofSeconds(60));
        config.update(new RiskRuleConfig.Settings(0, false, BigDecimal.TEN, 3, null, BigDecimal.ONE), NOW);
        assertThat(config.window(Duration.ofHours(1))).isEqualTo(Duration.ofHours(1));
        assertThat(config.isEnabled()).isFalse();
        assertThatThrownBy(() -> config.update(new RiskRuleConfig.Settings(101, true, null, null, null, null), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.update(new RiskRuleConfig.Settings(1, true, null, null, 0, null), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.update(new RiskRuleConfig.Settings(1, true, new BigDecimal("-1"), null, null, null), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void profileTracksAverageAndLatestPinLock() {
        UserRiskProfile p = UserRiskProfile.empty(UUID.randomUUID(), NOW);
        assertThat(p.averageAmount()).isEqualByComparingTo("0");
        p.recordCompleted(new BigDecimal("100"), NOW);
        p.recordCompleted(new BigDecimal("201"), NOW);
        assertThat(p.averageAmount()).isEqualByComparingTo("150.50");
        assertThat(p.getCompletedMax()).isEqualByComparingTo("201");
        p.recordPinLock(NOW, NOW);
        p.recordPinLock(NOW.minusSeconds(10), NOW);
        assertThat(p.getLastPinLockAt()).isEqualTo(NOW);
    }
}
