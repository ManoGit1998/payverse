package com.payverse.transaction.entity;

import com.payverse.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A bill split between the creator and participants; each participant's share is a {@link MoneyRequest}. */
@Entity
@Table(name = "split_bills")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SplitBill {

    @Id
    private UUID id;

    @Column(name = "creator_user_id", nullable = false)
    private UUID creatorUserId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal total;

    @Column(name = "creator_share", nullable = false, precision = 19, scale = 2)
    private BigDecimal creatorShare;

    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static SplitBill create(UUID creatorUserId, Money total, Money creatorShare, String note, Instant now) {
        SplitBill bill = new SplitBill();
        bill.id = UUID.randomUUID();
        bill.creatorUserId = creatorUserId;
        bill.total = total.amount();
        bill.creatorShare = creatorShare.amount();
        bill.note = note;
        bill.createdAt = now;
        return bill;
    }

    public Money getTotalMoney() {
        return Money.inr(total);
    }

    public Money getCreatorShareMoney() {
        return Money.inr(creatorShare);
    }
}
