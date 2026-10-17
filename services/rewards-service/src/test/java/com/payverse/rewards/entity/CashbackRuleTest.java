package com.payverse.rewards.entity;

import com.payverse.common.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CashbackRuleTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    static CashbackRule.Definition def(RewardType type, BigDecimal flat, BigDecimal percent, BigDecimal min, BigDecimal max) {
        return new CashbackRule.Definition("rule", RuleTrigger.P2P_TRANSFER, type, flat, percent, min, max, new BigDecimal("100"),
                new BigDecimal("10000"), false, 3, null, null, new BigDecimal("1000"), 5, true);
    }

    @Test
    void flatPercentAndScratchRewards() {
        CashbackRule flat = CashbackRule.create("FLAT", def(RewardType.FLAT, new BigDecimal("25"), null, null, null), NOW);
        assertThat(flat.rewardFor(Money.inr("500"), new Random(1))).isEqualTo(Money.inr("25"));

        CashbackRule pct = CashbackRule.create("PCT", def(RewardType.PERCENT, null, new BigDecimal("1"), null, new BigDecimal("50")), NOW);
        assertThat(pct.rewardFor(Money.inr("1234"), new Random(1))).isEqualTo(Money.inr("12.34"));
        assertThat(pct.rewardFor(Money.inr("9000"), new Random(1))).isEqualTo(Money.inr("50"));

        CashbackRule uncapped = CashbackRule.create("PCT2", def(RewardType.PERCENT, null, new BigDecimal("2"), null, null), NOW);
        assertThat(uncapped.rewardFor(Money.inr("9000"), new Random(1))).isEqualTo(Money.inr("180"));

        CashbackRule scratch = CashbackRule.create("SC", def(RewardType.SCRATCH_CARD, null, null, new BigDecimal("1"), new BigDecimal("25")), NOW);
        Random random = new Random(42);
        for (int i = 0; i < 200; i++) {
            Money m = scratch.rewardFor(Money.inr("100"), random);
            assertThat(m.amount()).isBetween(new BigDecimal("1"), new BigDecimal("25"));
            assertThat(m.amount().stripTrailingZeros().scale()).isLessThanOrEqualTo(0);
        }
        CashbackRule fractional = CashbackRule.create("SC2", def(RewardType.SCRATCH_CARD, null, null, new BigDecimal("1.20"), new BigDecimal("1.80")), NOW);
        assertThat(fractional.rewardFor(Money.inr("100"), random)).isEqualTo(Money.inr("1.20"));
        assertThat(scratch.spentMoney()).isEqualTo(Money.inr("0"));
        assertThat(scratch.getCurrency()).isEqualTo("INR");
    }

    @Test
    void conditions() {
        CashbackRule rule = CashbackRule.create("R", def(RewardType.FLAT, BigDecimal.TEN, null, null, null), NOW);
        assertThat(rule.matches(Money.inr("100"), false, NOW)).isTrue();
        assertThat(rule.matches(Money.inr("99.99"), false, NOW)).isFalse();
        assertThat(rule.matches(Money.inr("10000.01"), false, NOW)).isFalse();

        rule.update(new CashbackRule.Definition("r", RuleTrigger.ADD_MONEY, RewardType.FLAT, BigDecimal.TEN, null, null, null, null, null,
                true, null, NOW, NOW.plusSeconds(60), null, 0, true), NOW);
        assertThat(rule.matches(Money.inr("1"), false, NOW)).isFalse();
        assertThat(rule.matches(Money.inr("1"), true, NOW)).isTrue();
        assertThat(rule.matches(Money.inr("1"), true, NOW.minusSeconds(1))).isFalse();
        assertThat(rule.matches(Money.inr("1"), true, NOW.plusSeconds(60))).isFalse();
        assertThat(rule.getTrigger()).isEqualTo(RuleTrigger.ADD_MONEY);

        rule.update(new CashbackRule.Definition("r", RuleTrigger.ADD_MONEY, RewardType.FLAT, BigDecimal.TEN, null, null, null, null, null,
                false, null, null, null, null, 0, false), NOW);
        assertThat(rule.matches(Money.inr("1"), true, NOW)).isFalse();
    }

    @Test
    void invalidDefinitionsAreRejected() {
        assertThatThrownBy(() -> CashbackRule.create("X", def(RewardType.FLAT, null, null, null, null), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CashbackRule.create("X", def(RewardType.PERCENT, null, new BigDecimal("101"), null, null), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CashbackRule.create("X", def(RewardType.SCRATCH_CARD, null, null, new BigDecimal("5"), new BigDecimal("1")), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CashbackRule.create("X", new CashbackRule.Definition("r", RuleTrigger.ADD_MONEY, RewardType.FLAT, BigDecimal.TEN,
                null, null, null, new BigDecimal("10"), new BigDecimal("5"), false, null, null, null, null, 0, true), NOW))
                .hasMessageContaining("minTxnAmount");
        assertThatThrownBy(() -> CashbackRule.create("X", new CashbackRule.Definition("r", RuleTrigger.ADD_MONEY, RewardType.FLAT, BigDecimal.TEN,
                null, null, null, null, null, false, null, NOW, NOW, null, 0, true), NOW)).hasMessageContaining("activeFrom");
        assertThatThrownBy(() -> CashbackRule.create("X", new CashbackRule.Definition("r", RuleTrigger.ADD_MONEY, RewardType.FLAT, BigDecimal.TEN,
                null, null, null, null, null, false, 0, null, null, null, 0, true), NOW)).hasMessageContaining("dailyCap");
        assertThatThrownBy(() -> CashbackRule.create("X", new CashbackRule.Definition("r", RuleTrigger.ADD_MONEY, RewardType.FLAT, BigDecimal.TEN,
                null, null, null, null, null, false, null, null, null, new BigDecimal("-1"), 0, true), NOW)).hasMessageContaining("budget");
    }
}
