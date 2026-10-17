package com.payverse.admin.entity;

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
 * Maker-checker refund of a completed transfer: REQUESTED (maker: SUPPORT or ADMIN) → APPROVED by a
 * different ADMIN (checker) → executed by transaction-service → COMPLETED, or FAILED (e.g. the payee
 * already spent the money), or PROCESSING while transaction-service is unreachable (retried).
 * REQUESTED → REJECTED ends it.
 */
@Entity
@Table(name = "refund_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundRequest {

    @Id
    private UUID id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Column(name = "dispute_id", updatable = false)
    private UUID disputeId;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private String requestedBy;

    @Column(name = "decided_by")
    private String decidedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RefundStatus status;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    private long version;

    public static RefundRequest request(UUID transactionId, Money amount, String reason, UUID disputeId, String requestedBy, Instant now) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Refund amount must be positive");
        }
        RefundRequest r = new RefundRequest();
        r.id = UUID.randomUUID();
        r.transactionId = transactionId;
        r.amount = amount.amount();
        r.currency = amount.currency().getCurrencyCode();
        r.reason = reason == null || reason.isBlank() ? "refund" : reason.length() <= 200 ? reason : reason.substring(0, 200);
        r.disputeId = disputeId;
        r.requestedBy = requestedBy;
        r.status = RefundStatus.REQUESTED;
        r.createdAt = now;
        return r;
    }

    public Money getMoney() {
        return Money.of(amount, currency);
    }

    /** The checker. Throws if the checker is the maker. */
    public void approve(String approver, Instant now) {
        decide(approver, now);
        status = RefundStatus.APPROVED;
        nextAttemptAt = now;
    }

    public void reject(String approver, Instant now) {
        decide(approver, now);
        status = RefundStatus.REJECTED;
    }

    public boolean isExecutable(Instant now) {
        return (status == RefundStatus.APPROVED || status == RefundStatus.PROCESSING) && nextAttemptAt != null && !now.isBefore(nextAttemptAt);
    }

    public void completed(Instant now) {
        requireExecutable();
        attempts++;
        status = RefundStatus.COMPLETED;
        nextAttemptAt = null;
        completedAt = now;
    }

    public void failed(String code, Instant now) {
        requireExecutable();
        attempts++;
        status = RefundStatus.FAILED;
        failureCode = code;
        nextAttemptAt = null;
        completedAt = now;
    }

    /** transaction-service unreachable or the refund is still running there: try again (2^n s, max 5 min). */
    public void retryLater(String code, Instant now) {
        requireExecutable();
        attempts++;
        status = RefundStatus.PROCESSING;
        failureCode = code;
        nextAttemptAt = now.plus(Duration.ofSeconds(Math.min(1L << Math.min(attempts, 8), 300)));
    }

    private void decide(String approver, Instant now) {
        if (status != RefundStatus.REQUESTED) {
            throw new IllegalStateException("Refund " + id + " is already " + status);
        }
        if (approver.equals(requestedBy)) {
            throw new IllegalArgumentException("Maker-checker: the requester cannot decide their own refund");
        }
        decidedBy = approver;
        decidedAt = now;
    }

    private void requireExecutable() {
        if (status != RefundStatus.APPROVED && status != RefundStatus.PROCESSING) {
            throw new IllegalStateException("Refund " + id + " is " + status);
        }
    }
}
