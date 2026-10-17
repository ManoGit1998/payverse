package com.payverse.rewards.entity;

import com.payverse.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * A cashback rule: which events qualify (trigger + conditions), what they earn (scratch card,
 * flat or percent) and the budget. {@code spent} is only changed by atomic budget queries, so it
 * is read-only here and {@link DynamicUpdate} keeps admin edits from overwriting it.
 */
@Entity
@Table(name = "cashback_rules")
@DynamicUpdate
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CashbackRule {

    /** Typed rule definition, used to create and edit rules. */
    public record Definition(String name, RuleTrigger trigger, RewardType rewardType, BigDecimal flatAmount, BigDecimal percent,
                             BigDecimal minReward, BigDecimal maxReward, BigDecimal minTxnAmount, BigDecimal maxTxnAmount,
                             boolean firstTimeOnly, Integer dailyCapPerUser, Instant activeFrom, Instant activeTo,
                             BigDecimal budget, int priority, boolean active) {
    }

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false)
    private RuleTrigger trigger;

    @Enumerated(EnumType.STRING)
    @Column(name = "reward_type", nullable = false)
    private RewardType rewardType;

    @Column(name = "flat_amount")
    private BigDecimal flatAmount;

    private BigDecimal percent;

    @Column(name = "min_reward")
    private BigDecimal minReward;

    @Column(name = "max_reward")
    private BigDecimal maxReward;

    @Column(name = "min_txn_amount")
    private BigDecimal minTxnAmount;

    @Column(name = "max_txn_amount")
    private BigDecimal maxTxnAmount;

    @Column(name = "first_time_only", nullable = false)
    private boolean firstTimeOnly;

    @Column(name = "daily_cap_per_user")
    private Integer dailyCapPerUser;

    @Column(name = "active_from")
    private Instant activeFrom;

    @Column(name = "active_to")
    private Instant activeTo;

    private BigDecimal budget;

    @Column(insertable = false, updatable = false)
    private BigDecimal spent;

    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private boolean active;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public static CashbackRule create(String code, Definition definition, Instant now) {
        CashbackRule rule = new CashbackRule();
        rule.id = UUID.randomUUID();
        rule.code = code;
        rule.currency = Money.INR.getCurrencyCode();
        rule.createdAt = now;
        rule.spent = BigDecimal.ZERO;
        rule.apply(definition, now);
        return rule;
    }

    public void update(Definition definition, Instant now) {
        apply(definition, now);
    }

    private void apply(Definition d, Instant now) {
        validate(d);
        name = d.name();
        trigger = d.trigger();
        rewardType = d.rewardType();
        flatAmount = d.flatAmount();
        percent = d.percent();
        minReward = d.minReward();
        maxReward = d.maxReward();
        minTxnAmount = d.minTxnAmount();
        maxTxnAmount = d.maxTxnAmount();
        firstTimeOnly = d.firstTimeOnly();
        dailyCapPerUser = d.dailyCapPerUser();
        activeFrom = d.activeFrom();
        activeTo = d.activeTo();
        budget = d.budget();
        priority = d.priority();
        active = d.active();
        updatedAt = now;
    }

    private static void validate(Definition d) {
        switch (d.rewardType()) {
            case FLAT -> require(positive(d.flatAmount()), "FLAT rules need a positive flatAmount");
            case PERCENT -> require(positive(d.percent()) && d.percent().compareTo(BigDecimal.valueOf(100)) <= 0,
                    "PERCENT rules need a percent in (0, 100]");
            case SCRATCH_CARD -> require(positive(d.minReward()) && positive(d.maxReward()) && d.minReward().compareTo(d.maxReward()) <= 0,
                    "SCRATCH_CARD rules need 0 < minReward <= maxReward");
        }
        require(d.minTxnAmount() == null || d.maxTxnAmount() == null || d.minTxnAmount().compareTo(d.maxTxnAmount()) <= 0,
                "minTxnAmount must not exceed maxTxnAmount");
        require(d.activeFrom() == null || d.activeTo() == null || d.activeFrom().isBefore(d.activeTo()),
                "activeFrom must be before activeTo");
        require(d.dailyCapPerUser() == null || d.dailyCapPerUser() > 0, "dailyCapPerUser must be positive");
        require(d.budget() == null || d.budget().signum() >= 0, "budget must not be negative");
    }

    /** Static conditions: active window, amount range and first-time-only. Caps and budget are checked by the engine. */
    public boolean matches(Money txnAmount, boolean firstTime, Instant now) {
        if (!active || (activeFrom != null && now.isBefore(activeFrom)) || (activeTo != null && !now.isBefore(activeTo))) {
            return false;
        }
        if (minTxnAmount != null && txnAmount.amount().compareTo(minTxnAmount) < 0) {
            return false;
        }
        if (maxTxnAmount != null && txnAmount.amount().compareTo(maxTxnAmount) > 0) {
            return false;
        }
        return !firstTimeOnly || firstTime;
    }

    /**
     * The reward for a qualifying amount: FLAT → flatAmount; PERCENT → percent of the amount, capped
     * at maxReward; SCRATCH_CARD → a random whole-rupee amount in [minReward, maxReward].
     */
    public Money rewardFor(Money txnAmount, RandomGenerator random) {
        return switch (rewardType) {
            case FLAT -> money(flatAmount);
            case PERCENT -> {
                Money reward = txnAmount.percentage(percent);
                yield maxReward == null ? reward : reward.min(money(maxReward));
            }
            case SCRATCH_CARD -> {
                long low = minReward.setScale(0, RoundingMode.CEILING).longValueExact();
                long high = maxReward.setScale(0, RoundingMode.FLOOR).longValueExact();
                yield high < low ? money(minReward) : money(BigDecimal.valueOf(random.nextLong(low, high + 1)));
            }
        };
    }

    public Money spentMoney() {
        return money(spent == null ? BigDecimal.ZERO : spent);
    }

    private Money money(BigDecimal value) {
        return Money.of(value, currency);
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
