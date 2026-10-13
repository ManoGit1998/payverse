package com.payverse.auth.entity;

import com.payverse.auth.model.PinVerification;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A user's Argon2id-hashed transaction PIN with brute-force protection: after {@link #MAX_ATTEMPTS}
 * consecutive failures the PIN is locked for {@link #LOCK_DURATION}. Hashing happens in the service;
 * this entity only tracks attempts and lock state.
 */
@Entity
@Table(name = "transaction_pins")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransactionPin {

    public static final int MAX_ATTEMPTS = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(30);

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "pin_hash", nullable = false)
    private String pinHash;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public static TransactionPin create(UUID userId, String pinHash, Instant now) {
        TransactionPin pin = new TransactionPin();
        pin.userId = userId;
        pin.pinHash = pinHash;
        pin.updatedAt = now;
        return pin;
    }

    /**
     * Records one verification attempt. Callers must not compare the hash while {@link #isLocked}
     * (a locked PIN refuses even the correct value).
     */
    public PinVerification recordAttempt(boolean matched, Instant now) {
        if (isLocked(now)) {
            return new PinVerification.Locked(lockedUntil, false);
        }
        updatedAt = now;
        if (matched) {
            failedAttempts = 0;
            lockedUntil = null;
            return new PinVerification.Valid();
        }
        failedAttempts++;
        if (failedAttempts >= MAX_ATTEMPTS) {
            lockedUntil = now.plus(LOCK_DURATION);
            failedAttempts = 0;
            return new PinVerification.Locked(lockedUntil, true);
        }
        return new PinVerification.Incorrect(MAX_ATTEMPTS - failedAttempts);
    }

    /** Sets a new PIN hash and clears any lock. */
    public void replace(String newPinHash, Instant now) {
        pinHash = newPinHash;
        failedAttempts = 0;
        lockedUntil = null;
        updatedAt = now;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }
}
