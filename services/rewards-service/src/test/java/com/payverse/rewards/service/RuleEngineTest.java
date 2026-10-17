package com.payverse.rewards.service;

import com.payverse.common.money.Money;
import com.payverse.rewards.config.RewardsProperties;
import com.payverse.rewards.entity.CashbackRule;
import com.payverse.rewards.entity.RewardSource;
import com.payverse.rewards.entity.RewardType;
import com.payverse.rewards.entity.RuleTrigger;
import com.payverse.rewards.repository.CashbackRuleRepository;
import com.payverse.rewards.repository.RewardRepository;
import com.payverse.rewards.repository.ScratchCardRepository;
import com.payverse.rewards.repository.UserActivityRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuleEngineTest {

    // 2026-10-17 05:00 IST
    static final Instant NOW = Instant.parse("2026-10-16T23:30:00Z");
    final CashbackRuleRepository rules = mock(CashbackRuleRepository.class);
    final RewardRepository rewards = mock(RewardRepository.class);
    final ScratchCardRepository cards = mock(ScratchCardRepository.class);
    final UserActivityRepository activity = mock(UserActivityRepository.class);
    final RewardEventPublisher events = mock(RewardEventPublisher.class);
    final RuleEngine engine = new RuleEngine(rules, rewards, cards, activity, events,
            new RewardsProperties(null, null, null, null, null, null), new Random(3), Clock.fixed(NOW, ZoneOffset.UTC));
    final UUID user = UUID.randomUUID();

    static CashbackRule rule(String code, RewardType type, boolean firstTime, Integer cap, int priority) {
        return CashbackRule.create(code, new CashbackRule.Definition(code, RuleTrigger.P2P_TRANSFER, type,
                type == RewardType.FLAT ? new BigDecimal("25") : null, null,
                type == RewardType.SCRATCH_CARD ? BigDecimal.ONE : null, type == RewardType.SCRATCH_CARD ? new BigDecimal("25") : null,
                new BigDecimal("100"), null, firstTime, cap, null, null, null, priority, true), NOW);
    }

    RuleEngine.QualifyingEvent event(String amount) {
        return new RuleEngine.QualifyingEvent(RuleTrigger.P2P_TRANSFER, user, RewardSource.TRANSACTION, UUID.randomUUID().toString(),
                Money.inr(amount));
    }

    @Test
    void firstMatchingRuleWithBudgetWins() {
        CashbackRule firstTimeOnly = rule("FIRST", RewardType.FLAT, true, null, 30);
        CashbackRule exhausted = rule("BROKE", RewardType.FLAT, false, null, 20);
        CashbackRule scratch = rule("SCRATCH", RewardType.SCRATCH_CARD, false, 3, 10);
        when(activity.increment(user, RuleTrigger.P2P_TRANSFER)).thenReturn(2L);
        when(rules.findByTriggerAndActiveTrueOrderByPriorityDescCodeAsc(RuleTrigger.P2P_TRANSFER))
                .thenReturn(List.of(firstTimeOnly, exhausted, scratch));
        when(rules.reserveBudget(eq(exhausted.getId()), any())).thenReturn(0);
        when(rules.reserveBudget(eq(scratch.getId()), any())).thenReturn(1);
        when(cards.save(any())).thenAnswer(i -> i.getArgument(0));

        RuleEngine.Outcome outcome = engine.evaluate(event("150"));

        assertThat(outcome).isInstanceOfSatisfying(RuleEngine.Outcome.Card.class, c -> {
            assertThat(c.card().getRuleCode()).isEqualTo("SCRATCH");
            assertThat(c.card().getMoney().amount()).isBetween(BigDecimal.ONE, new BigDecimal("25"));
        });
        verify(events).scratchCardIssued(any());
        // the daily cap counts cards since midnight IST
        verify(cards).countByUserIdAndRuleCodeAndCreatedAtGreaterThanEqual(user, "SCRATCH", Instant.parse("2026-10-16T18:30:00Z"));
    }

    @Test
    void flatRewardForFirstTimeAndNothingWhenCapped() {
        CashbackRule first = rule("FIRST", RewardType.FLAT, true, 1, 30);
        when(activity.increment(user, RuleTrigger.P2P_TRANSFER)).thenReturn(1L);
        when(rules.findByTriggerAndActiveTrueOrderByPriorityDescCodeAsc(RuleTrigger.P2P_TRANSFER)).thenReturn(List.of(first));
        when(rules.reserveBudget(eq(first.getId()), any())).thenReturn(1);
        when(rewards.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(engine.evaluate(event("100"))).isInstanceOfSatisfying(RuleEngine.Outcome.Cashback.class,
                c -> assertThat(c.reward().getMoney()).isEqualTo(Money.inr("25")));

        when(rewards.countByUserIdAndRuleCodeAndCreatedAtGreaterThanEqual(eq(user), eq("FIRST"), any())).thenReturn(1L);
        assertThat(engine.evaluate(event("100"))).isInstanceOf(RuleEngine.Outcome.Nothing.class);
        assertThat(engine.evaluate(event("99"))).isInstanceOf(RuleEngine.Outcome.Nothing.class);
    }
}
