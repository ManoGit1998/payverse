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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One money reward in the rewards ledger. PENDING_CREDIT rewards are credited to the wallet by
 * the credit job (idempotency key {@code reward:<id>}); failures back off and end in FAILED.
 */
@Entity
@Table(name = "rewards")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reward {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "rule_code", nullable = false)
    private String ruleCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private RewardSource sourceType;

    @Column(name = "source_id", nullable = false)
    private String sourceId;

    @Column(nullable = false)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RewardStatus status;

    @Column(name = "journal_id")
    private UUID journalId;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "credited_at")
    private Instant creditedAt;

    @Version
    private long version;

    public static Reward pending(UUID userId, String ruleCode, RewardSource sourceType, String sourceId, Money amount, Instant now) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("A reward must be positive");
        }
        Reward reward = new Reward();
        reward.id = UUID.randomUUID();
        reward.userId = userId;
        reward.ruleCode = ruleCode;
        reward.sourceType = sourceType;
        reward.sourceId = sourceId;
        reward.amount = amount.amount();
        reward.currency = amount.currency().getCurrencyCode();
        reward.status = RewardStatus.PENDING_CREDIT;
        reward.nextAttemptAt = now;
        reward.createdAt = now;
        return reward;
    }

    public Money getMoney() {
        return Money.of(amount, currency);
    }

    /** Wallet idempotency key of the credit (and, suffixed, of the clawback). */
    public String creditKey() {
        return "reward:" + id;
    }

    public boolean isDue(Instant now) {
        return status == RewardStatus.PENDING_CREDIT && nextAttemptAt != null && !now.isBefore(nextAttemptAt);
    }

    public void markCredited(UUID journal, Instant now) {
        requireStatus(RewardStatus.PENDING_CREDIT);
        attempts++;
        status = RewardStatus.CREDITED;
        journalId = journal;
        creditedAt = now;
        nextAttemptAt = null;
        lastError = null;
    }

    /** @return true if the reward is now FAILED (no more attempts) */
    public boolean recordCreditFailure(String error, int maxAttempts, Instant now) {
        requireStatus(RewardStatus.PENDING_CREDIT);
        attempts++;
        lastError = error == null ? null : error.length() <= 300 ? error : error.substring(0, 300);
        if (attempts >= maxAttempts) {
            status = RewardStatus.FAILED;
            nextAttemptAt = null;
            return true;
        }
        nextAttemptAt = now.plus(Duration.ofSeconds(5L << Math.min(attempts - 1, 10)));
        return false;
    }

    /** A reward that was never credited is simply voided on clawback. */
    public void voidPending() {
        requireStatus(RewardStatus.PENDING_CREDIT);
        status = RewardStatus.VOIDED;
        nextAttemptAt = null;
    }

    public void markClawedBack() {
        requireStatus(RewardStatus.CREDITED);
        status = RewardStatus.CLAWED_BACK;
    }

    public void markClawbackFailed(String error) {
        requireStatus(RewardStatus.CREDITED);
        status = RewardStatus.CLAWBACK_FAILED;
        lastError = error == null ? null : error.length() <= 300 ? error : error.substring(0, 300);
    }

    private void requireStatus(RewardStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Reward " + id + " is " + status + ", expected " + expected);
        }
    }
}
