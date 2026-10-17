package com.payverse.bill.entity;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One scheduled run of a mandate; {@code UNIQUE(mandate_id, scheduled_for)} makes each run happen once. */
@Entity
@Table(name = "mandate_executions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MandateExecution {

    @Id
    private UUID id;

    @Column(name = "mandate_id", nullable = false, updatable = false)
    private UUID mandateId;

    @Column(name = "scheduled_for", nullable = false, updatable = false)
    private Instant scheduledFor;

    @Column(name = "bill_payment_id")
    private UUID billPaymentId;

    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExecutionStatus status;

    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    private long version;

    public static MandateExecution start(UUID mandateId, Instant scheduledFor, Instant now) {
        MandateExecution e = new MandateExecution();
        e.id = UUID.randomUUID();
        e.mandateId = mandateId;
        e.scheduledFor = scheduledFor;
        e.status = ExecutionStatus.PENDING;
        e.createdAt = now;
        return e;
    }

    public void attach(UUID paymentId, Money paid) {
        billPaymentId = paymentId;
        amount = paid.amount();
    }

    /** @return false if the run was already completed (so its event was already published) */
    public boolean complete(ExecutionStatus result, String why, Instant now) {
        if (result == ExecutionStatus.PENDING) {
            throw new IllegalArgumentException("complete with a final status");
        }
        if (status != ExecutionStatus.PENDING) {
            return false;
        }
        status = result;
        reason = why == null || why.length() <= 200 ? why : why.substring(0, 200);
        completedAt = now;
        return true;
    }
}
