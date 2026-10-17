package com.payverse.fraud.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/** Per-user behaviour built from events: completed transfers (for amount anomalies) and the last PIN lock. */
@Entity
@Table(name = "user_risk_profiles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserRiskProfile {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "completed_count", nullable = false)
    private long completedCount;

    @Column(name = "completed_total", nullable = false)
    private BigDecimal completedTotal;

    @Column(name = "completed_max", nullable = false)
    private BigDecimal completedMax;

    @Column(name = "last_pin_lock_at")
    private Instant lastPinLockAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static UserRiskProfile empty(UUID userId, Instant now) {
        UserRiskProfile p = new UserRiskProfile();
        p.userId = userId;
        p.completedTotal = BigDecimal.ZERO;
        p.completedMax = BigDecimal.ZERO;
        p.updatedAt = now;
        return p;
    }

    public void recordCompleted(BigDecimal amount, Instant now) {
        completedCount++;
        completedTotal = completedTotal.add(amount);
        completedMax = completedMax.max(amount);
        updatedAt = now;
    }

    public void recordPinLock(Instant lockedAt, Instant now) {
        if (lastPinLockAt == null || lockedAt.isAfter(lastPinLockAt)) {
            lastPinLockAt = lockedAt;
        }
        updatedAt = now;
    }

    public BigDecimal averageAmount() {
        return completedCount == 0 ? BigDecimal.ZERO : completedTotal.divide(BigDecimal.valueOf(completedCount), 2, RoundingMode.HALF_EVEN);
    }
}
