package com.payverse.transaction.entity;

import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.transaction.exception.TransactionErrorCode;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** "Request money": the requester asks the payer; the payer pays (a normal transfer) or declines. */
@Entity
@Table(name = "money_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MoneyRequest {

    public static final Duration TTL = Duration.ofDays(7);

    @Id
    private UUID id;

    @Column(name = "requester_user_id", nullable = false)
    private UUID requesterUserId;

    @Column(name = "requester_ref")
    private String requesterRef;

    @Column(name = "payer_user_id", nullable = false)
    private UUID payerUserId;

    @Column(name = "payer_ref", nullable = false)
    private String payerRef;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MoneyRequestStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "split_bill_id")
    private UUID splitBillId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public static MoneyRequest create(UUID requesterUserId, String requesterRef, UUID payerUserId, String payerRef, Money amount,
                                      String note, UUID splitBillId, Instant now) {
        if (requesterUserId.equals(payerUserId)) {
            throw new PayverseException(TransactionErrorCode.TXN_SELF_TRANSFER, "You cannot request money from yourself");
        }
        if (!amount.isPositive()) {
            throw new PayverseException(TransactionErrorCode.TXN_INVALID_AMOUNT, null);
        }
        MoneyRequest request = new MoneyRequest();
        request.id = UUID.randomUUID();
        request.requesterUserId = requesterUserId;
        request.requesterRef = requesterRef;
        request.payerUserId = payerUserId;
        request.payerRef = payerRef;
        request.amount = amount.amount();
        request.note = note;
        request.status = MoneyRequestStatus.PENDING;
        request.expiresAt = now.plus(TTL);
        request.splitBillId = splitBillId;
        request.createdAt = now;
        request.updatedAt = now;
        return request;
    }

    public Money getAmountMoney() {
        return Money.inr(amount);
    }

    /** Lazily expires; true if it still awaits payment. */
    public boolean isPayable(Instant now) {
        if (status == MoneyRequestStatus.PENDING && !now.isBefore(expiresAt)) {
            status = MoneyRequestStatus.EXPIRED;
            updatedAt = now;
        }
        return status == MoneyRequestStatus.PENDING;
    }

    public void ensurePayable(Instant now) {
        if (!isPayable(now)) {
            throw new PayverseException(TransactionErrorCode.TXN_MONEY_REQUEST_NOT_PENDING, "Status is " + status);
        }
    }

    public void attachTransaction(UUID txnId, Instant now) {
        transactionId = txnId;
        updatedAt = now;
    }

    public void markPaid(UUID txnId, Instant now) {
        if (status == MoneyRequestStatus.PENDING || status == MoneyRequestStatus.EXPIRED) {
            status = MoneyRequestStatus.PAID; // money already moved: paid wins over a late expiry
            transactionId = txnId;
            updatedAt = now;
        }
    }

    public void decline(Instant now) {
        ensurePayable(now);
        status = MoneyRequestStatus.DECLINED;
        updatedAt = now;
    }

    public void cancel(Instant now) {
        ensurePayable(now);
        status = MoneyRequestStatus.CANCELLED;
        updatedAt = now;
    }
}
