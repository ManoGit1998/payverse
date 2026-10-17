package com.payverse.payment.entity;

import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.payment.exception.PaymentErrorCode;
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
 * A UPI collect request: the payee asks the payer (by VPA) for money; the payer approves with their
 * PIN (which starts a transfer in transaction-service) or rejects. Expires after {@link #TTL}.
 */
@Entity
@Table(name = "collect_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollectRequest {

    public static final Duration TTL = Duration.ofDays(1);

    @Id
    private UUID id;

    @Column(name = "payer_vpa", nullable = false)
    private String payerVpa;

    @Column(name = "payee_vpa", nullable = false)
    private String payeeVpa;

    @Column(name = "payer_user_id", nullable = false)
    private UUID payerUserId;

    @Column(name = "payee_user_id", nullable = false)
    private UUID payeeUserId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CollectStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public static CollectRequest create(Vpa payer, Vpa payee, Money amount, String note, Instant now) {
        if (payer.getUserId().equals(payee.getUserId())) {
            throw new PayverseException(PaymentErrorCode.PAYMENT_SELF_COLLECT, null);
        }
        CollectRequest request = new CollectRequest();
        request.id = UUID.randomUUID();
        request.payerVpa = payer.getHandle();
        request.payeeVpa = payee.getHandle();
        request.payerUserId = payer.getUserId();
        request.payeeUserId = payee.getUserId();
        request.amount = amount.amount();
        request.currency = "INR";
        request.note = note;
        request.status = CollectStatus.PENDING;
        request.expiresAt = now.plus(TTL);
        request.createdAt = now;
        request.updatedAt = now;
        return request;
    }

    public Money getAmountMoney() {
        return Money.inr(amount);
    }

    /** Lazily expires; returns true if it still awaits the payer. */
    public boolean isActionable(Instant now) {
        if (status == CollectStatus.PENDING && !now.isBefore(expiresAt)) {
            status = CollectStatus.EXPIRED;
            updatedAt = now;
        }
        return status == CollectStatus.PENDING;
    }

    public void ensureActionable(Instant now) {
        if (!isActionable(now)) {
            throw new PayverseException(PaymentErrorCode.PAYMENT_COLLECT_NOT_PENDING, "Status is " + status);
        }
    }

    public void reject(Instant now) {
        ensureActionable(now);
        status = CollectStatus.REJECTED;
        updatedAt = now;
    }

    /** Records the outcome of the transfer started on approval. */
    public void recordTransfer(UUID txnId, String transferStatus, Instant now) {
        transactionId = txnId;
        status = switch (transferStatus) {
            case "SUCCESS" -> CollectStatus.APPROVED;
            case "FAILED" -> CollectStatus.PENDING; // payer may retry (e.g. after topping up)
            default -> CollectStatus.PROCESSING;
        };
        updatedAt = now;
    }

    /** Final outcome from the transaction events, for transfers that were still PENDING when approved. */
    public void settle(boolean success, Instant now) {
        if (status == CollectStatus.PROCESSING) {
            status = success ? CollectStatus.APPROVED : CollectStatus.FAILED;
            updatedAt = now;
        }
    }
}
