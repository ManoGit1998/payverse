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
import java.util.Optional;
import java.util.UUID;

/** A scratch card. Its amount is decided (and budgeted) at issue time but only revealed once scratched. */
@Entity
@Table(name = "scratch_cards")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScratchCard {

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
    private ScratchCardStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "scratched_at")
    private Instant scratchedAt;

    @Column(name = "reward_id")
    private UUID rewardId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private long version;

    public static ScratchCard issue(UUID userId, String ruleCode, RewardSource sourceType, String sourceId, Money amount,
                                    Duration validity, Instant now) {
        ScratchCard card = new ScratchCard();
        card.id = UUID.randomUUID();
        card.userId = userId;
        card.ruleCode = ruleCode;
        card.sourceType = sourceType;
        card.sourceId = sourceId;
        card.amount = amount.amount();
        card.currency = amount.currency().getCurrencyCode();
        card.status = ScratchCardStatus.UNSCRATCHED;
        card.expiresAt = now.plus(validity);
        card.createdAt = now;
        return card;
    }

    public Money getMoney() {
        return Money.of(amount, currency);
    }

    /** The amount, only once the card has been scratched. */
    public Optional<Money> revealedAmount() {
        return status == ScratchCardStatus.SCRATCHED ? Optional.of(getMoney()) : Optional.empty();
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public void scratch(UUID reward, Instant now) {
        if (status != ScratchCardStatus.UNSCRATCHED) {
            throw new IllegalStateException("Card " + id + " is " + status);
        }
        if (isExpired(now)) {
            throw new IllegalStateException("Card " + id + " expired");
        }
        status = ScratchCardStatus.SCRATCHED;
        scratchedAt = now;
        rewardId = reward;
    }

    public void expire() {
        if (status == ScratchCardStatus.UNSCRATCHED) {
            status = ScratchCardStatus.EXPIRED;
        }
    }

    /** Clawback before scratching: the card can no longer be scratched. */
    public void voidCard() {
        if (status == ScratchCardStatus.UNSCRATCHED) {
            status = ScratchCardStatus.VOIDED;
        }
    }
}
