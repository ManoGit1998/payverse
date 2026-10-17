package com.payverse.rewards.service;

import com.payverse.common.money.Money;
import com.payverse.rewards.config.RewardsProperties;
import com.payverse.rewards.entity.CashbackRule;
import com.payverse.rewards.entity.Reward;
import com.payverse.rewards.entity.RewardSource;
import com.payverse.rewards.entity.RewardType;
import com.payverse.rewards.entity.RuleTrigger;
import com.payverse.rewards.entity.ScratchCard;
import com.payverse.rewards.repository.CashbackRuleRepository;
import com.payverse.rewards.repository.RewardRepository;
import com.payverse.rewards.repository.ScratchCardRepository;
import com.payverse.rewards.repository.UserActivityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Cashback rules engine. For a qualifying event it walks the trigger's active rules by priority
 * and applies the <b>first</b> one whose conditions hold (active window, amount range,
 * first-time-only, daily cap per user) and whose budget can still cover the reward. At most one
 * reward per event.
 */
@Service
public class RuleEngine {

    public sealed interface Outcome {
        record Card(ScratchCard card) implements Outcome {
        }

        record Cashback(Reward reward) implements Outcome {
        }

        record Nothing() implements Outcome {
        }
    }

    public record QualifyingEvent(RuleTrigger trigger, UUID userId, RewardSource sourceType, String sourceId, Money amount) {
    }

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final CashbackRuleRepository rules;
    private final RewardRepository rewards;
    private final ScratchCardRepository cards;
    private final UserActivityRepository activity;
    private final RewardEventPublisher events;
    private final RewardsProperties properties;
    private final RandomGenerator random;
    private final Clock clock;

    public RuleEngine(CashbackRuleRepository rules, RewardRepository rewards, ScratchCardRepository cards, UserActivityRepository activity,
                      RewardEventPublisher events, RewardsProperties properties, RandomGenerator random, Clock clock) {
        this.rules = rules;
        this.rewards = rewards;
        this.cards = cards;
        this.activity = activity;
        this.events = events;
        this.properties = properties;
        this.random = random;
        this.clock = clock;
    }

    /** Joins the consumer's transaction (the event is processed once). */
    @Transactional
    public Outcome evaluate(QualifyingEvent event) {
        Instant now = clock.instant();
        boolean firstTime = activity.increment(event.userId(), event.trigger()) == 1;
        for (CashbackRule rule : rules.findByTriggerAndActiveTrueOrderByPriorityDescCodeAsc(event.trigger())) {
            if (!rule.matches(event.amount(), firstTime, now) || capReached(rule, event.userId(), now)) {
                continue;
            }
            Money reward = rule.rewardFor(event.amount(), random);
            if (!reward.isPositive() || rules.reserveBudget(rule.getId(), reward.amount()) == 0) {
                log.info("Rule {} skipped for {}: budget exhausted", rule.getCode(), event.sourceId());
                continue;
            }
            return rule.getRewardType() == RewardType.SCRATCH_CARD ? issueCard(rule, event, reward, now) : grant(rule, event, reward, now);
        }
        return new Outcome.Nothing();
    }

    private Outcome issueCard(CashbackRule rule, QualifyingEvent event, Money amount, Instant now) {
        ScratchCard card = cards.save(ScratchCard.issue(event.userId(), rule.getCode(), event.sourceType(), event.sourceId(), amount,
                properties.scratchCardValidity(), now));
        events.scratchCardIssued(card);
        return new Outcome.Card(card);
    }

    private Outcome grant(CashbackRule rule, QualifyingEvent event, Money amount, Instant now) {
        return new Outcome.Cashback(rewards.save(Reward.pending(event.userId(), rule.getCode(), event.sourceType(), event.sourceId(),
                amount, now)));
    }

    /** Daily caps count rewards (or cards) for this rule since midnight IST. */
    private boolean capReached(CashbackRule rule, UUID userId, Instant now) {
        if (rule.getDailyCapPerUser() == null) {
            return false;
        }
        Instant startOfDay = LocalDate.ofInstant(now, IST).atStartOfDay(IST).toInstant();
        long issued = rule.getRewardType() == RewardType.SCRATCH_CARD
                ? cards.countByUserIdAndRuleCodeAndCreatedAtGreaterThanEqual(userId, rule.getCode(), startOfDay)
                : rewards.countByUserIdAndRuleCodeAndCreatedAtGreaterThanEqual(userId, rule.getCode(), startOfDay);
        return issued >= rule.getDailyCapPerUser();
    }
}
