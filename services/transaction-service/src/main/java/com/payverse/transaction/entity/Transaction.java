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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A P2P transfer and the state of its orchestrated saga. Status changes go through
 * {@link #transitionTo}, which enforces {@link TransactionStatus}'s state machine and returns the
 * change so the service can record it in the history table.
 */
@Entity
@Table(name = "transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transaction {

    public record StatusChange(UUID transactionId, TransactionStatus from, TransactionStatus to, String reason, Instant at) {
    }

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransferSource source;

    @Column(name = "source_id")
    private UUID sourceId;

    @Column(name = "payer_user_id", nullable = false)
    private UUID payerUserId;

    @Column(name = "payee_user_id", nullable = false)
    private UUID payeeUserId;

    @Column(name = "payer_ref")
    private String payerRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "payee_type", nullable = false)
    private PayeeType payeeType;

    @Column(name = "payee_ref", nullable = false)
    private String payeeRef;

    @Column(name = "payee_name")
    private String payeeName;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "saga_step", nullable = false)
    private SagaStep sagaStep;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "debit_journal_id")
    private UUID debitJournalId;

    @Column(name = "credit_journal_id")
    private UUID creditJournalId;

    @Column(name = "compensation_journal_id")
    private UUID compensationJournalId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "device_id")
    private String deviceId;

    @Column(name = "risk_score")
    private Integer riskScore;

    @Column(name = "risk_decision")
    private String riskDecision;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    private long version;

    /** @param payee resolved payee (user id, how it was addressed, display name) */
    public static Transaction initiate(UUID payerUserId, String payerRef, Payee payee, Money amount, String note,
                                       TransferSource source, UUID sourceId, String idempotencyKey, String requestHash, Instant now) {
        if (!amount.isPositive()) {
            throw new PayverseException(TransactionErrorCode.TXN_INVALID_AMOUNT, "Amount must be positive");
        }
        if (payerUserId.equals(payee.userId())) {
            throw new PayverseException(TransactionErrorCode.TXN_SELF_TRANSFER, null);
        }
        Transaction txn = new Transaction();
        txn.id = UUID.randomUUID();
        txn.type = TransactionType.P2P;
        txn.source = source;
        txn.sourceId = sourceId;
        txn.payerUserId = payerUserId;
        txn.payerRef = payerRef;
        txn.payeeUserId = payee.userId();
        txn.payeeType = payee.type();
        txn.payeeRef = payee.ref();
        txn.payeeName = payee.name();
        txn.amount = amount.amount();
        txn.currency = "INR";
        txn.note = note == null || note.isBlank() ? null : note;
        txn.status = TransactionStatus.INITIATED;
        txn.sagaStep = SagaStep.NOT_STARTED;
        txn.idempotencyKey = idempotencyKey;
        txn.requestHash = requestHash;
        txn.createdAt = now;
        txn.updatedAt = now;
        return txn;
    }

    /** How the payee was resolved. */
    public record Payee(UUID userId, PayeeType type, String ref, String name) {
    }

    public Money getAmountMoney() {
        return Money.inr(amount);
    }

    public StatusChange transitionTo(TransactionStatus next, String reason, Instant now) {
        if (!status.canMoveTo(next)) {
            throw new PayverseException(TransactionErrorCode.TXN_ILLEGAL_STATE, status + " → " + next + " is not allowed",
                    Map.of("transactionId", id.toString(), "from", status.name(), "to", next.name()));
        }
        TransactionStatus previous = status;
        status = next;
        updatedAt = now;
        if (next == TransactionStatus.SUCCESS || next == TransactionStatus.FAILED || next == TransactionStatus.REVERSED) {
            completedAt = now;
            nextAttemptAt = null;
        }
        return new StatusChange(id, previous, next, reason, now);
    }

    /** The device the payer used (from the access token), for the fraud new-device rule. */
    public Transaction fromDevice(String device) {
        deviceId = device;
        return this;
    }

    public void recordRisk(int score, String decision) {
        riskScore = score;
        riskDecision = decision;
    }

    public StatusChange markPending(Instant now) {
        return transitionTo(TransactionStatus.PENDING, "risk check passed", now);
    }

    public void recordDebit(UUID journalId, Instant now) {
        requireStep(SagaStep.NOT_STARTED);
        debitJournalId = journalId;
        sagaStep = SagaStep.DEBITED;
        clearRetry(now);
    }

    public StatusChange recordCredit(UUID journalId, Instant now) {
        requireStep(SagaStep.DEBITED);
        creditJournalId = journalId;
        sagaStep = SagaStep.CREDITED;
        clearRetry(now);
        return transitionTo(TransactionStatus.SUCCESS, "payee credited", now);
    }

    /** The payee refused the credit: remember why, then the payer's debit must be reversed. */
    public void startCompensation(String code, String reason, Instant now) {
        requireStep(SagaStep.DEBITED);
        sagaStep = SagaStep.COMPENSATING;
        failureCode = code;
        failureReason = truncate(reason);
        clearRetry(now);
    }

    public StatusChange recordCompensation(UUID journalId, Instant now) {
        requireStep(SagaStep.COMPENSATING);
        compensationJournalId = journalId;
        sagaStep = SagaStep.COMPENSATED;
        clearRetry(now);
        return transitionTo(TransactionStatus.FAILED, "payer refunded: " + failureCode, now);
    }

    /** Fails before any money moved (risk block, payer debit refused). */
    public StatusChange fail(String code, String reason, Instant now) {
        if (sagaStep != SagaStep.NOT_STARTED) {
            throw new PayverseException(TransactionErrorCode.TXN_ILLEGAL_STATE, "Money already moved; compensate instead");
        }
        failureCode = code;
        failureReason = truncate(reason);
        return transitionTo(TransactionStatus.FAILED, code, now);
    }

    /** A step hit an infrastructure failure: retry later with exponential back-off (max 5 minutes). */
    public void scheduleRetry(String reason, Instant now) {
        attempts++;
        long delay = Math.min(1L << Math.min(attempts, 8), 300);
        nextAttemptAt = now.plus(Duration.ofSeconds(delay));
        failureReason = truncate(reason);
        updatedAt = now;
    }

    public boolean involves(UUID userId) {
        return payerUserId.equals(userId) || payeeUserId.equals(userId);
    }

    private void clearRetry(Instant now) {
        nextAttemptAt = null;
        updatedAt = now;
    }

    private void requireStep(SagaStep expected) {
        if (sagaStep != expected) {
            throw new PayverseException(TransactionErrorCode.TXN_ILLEGAL_STATE, "Saga step is " + sagaStep + ", expected " + expected);
        }
    }

    private static String truncate(String text) {
        return text == null || text.length() <= 200 ? text : text.substring(0, 200);
    }
}
