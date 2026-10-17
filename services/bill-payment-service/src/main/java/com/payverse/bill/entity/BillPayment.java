package com.payverse.bill.entity;

import com.payverse.common.money.Money;
import com.payverse.common.web.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
 * A bill payment and its little saga:
 * NOT_STARTED ─debit wallet─► DEBITED ─biller pays─► PAID (SUCCESS)
 *                                   └─biller declines─► REFUNDING ─refund wallet─► REFUNDED (FAILED).
 * A refused debit fails straight away. Infrastructure failures retry with back-off.
 */
@Entity
@Table(name = "bill_payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BillPayment {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "biller_code", nullable = false, updatable = false)
    private String billerCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private BillCategory category;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "account_ref", nullable = false, updatable = false)
    private String accountRef;

    @Column(name = "account_ref_masked", nullable = false, updatable = false)
    private String accountRefMasked;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BillPaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BillStep step;

    @Column(name = "debit_journal_id")
    private UUID debitJournalId;

    @Column(name = "refund_journal_id")
    private UUID refundJournalId;

    @Column(name = "biller_ref")
    private String billerRef;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_reason")
    private String failureReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PaymentSource source;

    @Column(name = "mandate_id", updatable = false)
    private UUID mandateId;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false)
    private String requestHash;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    private long version;

    public static BillPayment create(UUID userId, Biller biller, String accountRef, String accountRefMasked, Money amount,
                                     PaymentSource source, UUID mandateId, String idempotencyKey, String requestHash, Instant now) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        BillPayment p = new BillPayment();
        p.id = UUID.randomUUID();
        p.userId = userId;
        p.billerCode = biller.getCode();
        p.category = biller.getCategory();
        p.accountRef = accountRef;
        p.accountRefMasked = accountRefMasked;
        p.amount = amount.amount();
        p.currency = amount.currency().getCurrencyCode();
        p.status = BillPaymentStatus.PENDING;
        p.step = BillStep.NOT_STARTED;
        p.source = source;
        p.mandateId = mandateId;
        p.idempotencyKey = idempotencyKey;
        p.requestHash = requestHash;
        p.createdAt = now;
        p.updatedAt = now;
        return p;
    }

    public Money getMoney() {
        return Money.of(amount, currency);
    }

    public boolean isSettled() {
        return status != BillPaymentStatus.PENDING;
    }

    public void recordDebit(UUID journalId, Instant now) {
        require(BillStep.NOT_STARTED);
        debitJournalId = journalId;
        step = BillStep.DEBITED;
        clearRetry(now);
    }

    /** The wallet refused the debit (balance, limits, frozen): nothing moved. */
    public void failBeforeDebit(String code, String reason, Instant now) {
        require(BillStep.NOT_STARTED);
        finish(BillPaymentStatus.FAILED, code, reason, now);
    }

    public void recordBillerSuccess(String reference, Instant now) {
        require(BillStep.DEBITED);
        billerRef = reference;
        step = BillStep.PAID;
        finish(BillPaymentStatus.SUCCESS, null, null, now);
    }

    public void startRefund(String code, String reason, Instant now) {
        require(BillStep.DEBITED);
        step = BillStep.REFUNDING;
        failureCode = code;
        failureReason = truncate(reason);
        clearRetry(now);
    }

    public void recordRefund(UUID journalId, Instant now) {
        require(BillStep.REFUNDING);
        refundJournalId = journalId;
        step = BillStep.REFUNDED;
        finish(BillPaymentStatus.FAILED, failureCode, failureReason, now);
    }

    /** Infrastructure failure: retry after 2^n seconds (max 5 minutes). */
    public void scheduleRetry(String reason, Instant now) {
        attempts++;
        nextAttemptAt = now.plus(Duration.ofSeconds(Math.min(1L << Math.min(attempts, 8), 300)));
        failureReason = truncate(reason);
        updatedAt = now;
    }

    public boolean isDue(Instant now) {
        return status == BillPaymentStatus.PENDING && (nextAttemptAt == null || !now.isBefore(nextAttemptAt));
    }

    private void finish(BillPaymentStatus result, String code, String reason, Instant now) {
        status = result;
        failureCode = code;
        failureReason = truncate(reason);
        nextAttemptAt = null;
        updatedAt = now;
        completedAt = now;
    }

    private void clearRetry(Instant now) {
        nextAttemptAt = null;
        updatedAt = now;
    }

    private void require(BillStep expected) {
        if (step != expected) {
            throw new IllegalStateException("Bill payment " + id + " is at step " + step + ", expected " + expected);
        }
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 300 ? value : value.substring(0, 300);
    }
}
