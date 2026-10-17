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
import java.time.Instant;
import java.util.UUID;

/**
 * Auto-pay: on each run the current bill (fetch billers) or the fixed amount (recharges) is paid,
 * never more than {@code maxAmount}. Consent is given once, with the PIN, when the mandate is created.
 */
@Entity
@Table(name = "mandates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Mandate {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "biller_code", nullable = false, updatable = false)
    private String billerCode;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "account_ref", nullable = false, updatable = false)
    private String accountRef;

    @Column(name = "account_ref_masked", nullable = false, updatable = false)
    private String accountRefMasked;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private MandateFrequency frequency;

    @Column(name = "max_amount", nullable = false, updatable = false)
    private BigDecimal maxAmount;

    @Column(name = "fixed_amount", updatable = false)
    private BigDecimal fixedAmount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MandateStatus status;

    @Column(name = "next_run_at", nullable = false)
    private Instant nextRunAt;

    @Column(name = "run_count", nullable = false)
    private int runCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Version
    private long version;

    public static Mandate create(UUID userId, Biller biller, String accountRef, String accountRefMasked, MandateFrequency frequency,
                                 Money maxAmount, Money fixedAmount, Instant firstRun, Instant now) {
        if (!maxAmount.isPositive()) {
            throw new IllegalArgumentException("maxAmount must be positive");
        }
        if (!biller.isFetchSupported() && fixedAmount == null) {
            throw new IllegalArgumentException(biller.getName() + " has no bills to fetch: give the amount to recharge");
        }
        if (fixedAmount != null && (fixedAmount.isGreaterThan(maxAmount) || !fixedAmount.isPositive())) {
            throw new IllegalArgumentException("amount must be positive and not above maxAmount");
        }
        Mandate m = new Mandate();
        m.id = UUID.randomUUID();
        m.userId = userId;
        m.billerCode = biller.getCode();
        m.accountRef = accountRef;
        m.accountRefMasked = accountRefMasked;
        m.frequency = frequency;
        m.maxAmount = maxAmount.amount();
        m.fixedAmount = fixedAmount == null ? null : fixedAmount.amount();
        m.currency = maxAmount.currency().getCurrencyCode();
        m.status = MandateStatus.ACTIVE;
        m.nextRunAt = firstRun.isBefore(now) ? now : firstRun;
        m.createdAt = now;
        return m;
    }

    public Money getMaxMoney() {
        return Money.of(maxAmount, currency);
    }

    public Money getFixedMoney() {
        return fixedAmount == null ? null : Money.of(fixedAmount, currency);
    }

    public boolean isDue(Instant now) {
        return status == MandateStatus.ACTIVE && !now.isBefore(nextRunAt);
    }

    /** Claims the current run: returns its scheduled time and moves the schedule forward. */
    public Instant claimRun() {
        if (status != MandateStatus.ACTIVE) {
            throw new IllegalStateException("Mandate " + id + " is " + status);
        }
        Instant scheduled = nextRunAt;
        nextRunAt = frequency.next(nextRunAt);
        runCount++;
        return scheduled;
    }

    public void cancel(Instant now) {
        if (status == MandateStatus.ACTIVE) {
            status = MandateStatus.CANCELLED;
            cancelledAt = now;
        }
    }
}
