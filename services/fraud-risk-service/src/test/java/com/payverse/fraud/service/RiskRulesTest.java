package com.payverse.fraud.service;

import com.payverse.fraud.entity.RiskRuleConfig;
import com.payverse.fraud.model.RiskContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RiskRulesTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    final RiskRules rules = new RiskRules();
    final RiskRuleConfig defaults = RiskRuleConfig.create("X", "d", new RiskRuleConfig.Settings(10, true, null, null, null, null), NOW);

    static RiskContext ctx(String amount, long history, String avg, Instant deviceSeen, Instant pinLock, long count, String total) {
        return new RiskContext(UUID.randomUUID(), UUID.randomUUID(), null, "P2P", new BigDecimal(amount), "d", NOW, history,
                new BigDecimal(avg), Optional.ofNullable(deviceSeen), Optional.ofNullable(pinLock),
                (Duration w) -> new RiskContext.VelocityWindow(count, new BigDecimal(total)));
    }

    @Test
    void velocityRules() {
        assertThat(rules.velocityCount().triggered(ctx("1", 0, "0", null, null, 5, "0"), defaults)).isFalse();
        assertThat(rules.velocityCount().triggered(ctx("1", 0, "0", null, null, 6, "0"), defaults)).isTrue();
        assertThat(rules.velocityAmount().triggered(ctx("1", 0, "0", null, null, 1, "50000"), defaults)).isFalse();
        assertThat(rules.velocityAmount().triggered(ctx("1", 0, "0", null, null, 1, "50000.01"), defaults)).isTrue();
        assertThat(rules.velocityCount().code()).isEqualTo("VELOCITY_COUNT");
        assertThat(rules.velocityCount().toString()).contains("VELOCITY_COUNT");
    }

    @Test
    void amountRules() {
        assertThat(rules.amountAnomaly().triggered(ctx("1000", 3, "100", null, null, 1, "0"), defaults)).isTrue();
        assertThat(rules.amountAnomaly().triggered(ctx("1000", 2, "100", null, null, 1, "0"), defaults)).isFalse(); // not enough history
        assertThat(rules.amountAnomaly().triggered(ctx("999", 3, "100", null, null, 1, "0"), defaults)).isFalse();
        assertThat(rules.amountAnomaly().triggered(ctx("1000", 3, "200", null, null, 1, "0"), defaults)).isFalse(); // exactly 5x
        assertThat(rules.largeAmount().triggered(ctx("50000", 0, "0", null, null, 1, "0"), defaults)).isTrue();
        assertThat(rules.largeAmount().triggered(ctx("49999.99", 0, "0", null, null, 1, "0"), defaults)).isFalse();
    }

    @Test
    void deviceAndPinRules() {
        assertThat(rules.newDevice().triggered(ctx("2000", 0, "0", NOW.minusSeconds(3600), null, 1, "0"), defaults)).isTrue();
        assertThat(rules.newDevice().triggered(ctx("1999", 0, "0", NOW.minusSeconds(3600), null, 1, "0"), defaults)).isFalse();
        assertThat(rules.newDevice().triggered(ctx("2000", 0, "0", NOW.minus(Duration.ofDays(2)), null, 1, "0"), defaults)).isFalse();
        assertThat(rules.newDevice().triggered(ctx("2000", 0, "0", null, null, 1, "0"), defaults)).isFalse();
        assertThat(rules.recentPinLock().triggered(ctx("1", 0, "0", null, NOW.minusSeconds(60), 1, "0"), defaults)).isTrue();
        assertThat(rules.recentPinLock().triggered(ctx("1", 0, "0", null, NOW.minus(Duration.ofDays(2)), 1, "0"), defaults)).isFalse();
        assertThat(rules.recentPinLock().triggered(ctx("1", 0, "0", null, null, 1, "0"), defaults)).isFalse();
    }
}
