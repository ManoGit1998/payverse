package com.payverse.auth.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A user's hashed transaction PIN with brute-force protection: after {@link #MAX_ATTEMPTS}
 * consecutive failures the PIN is locked for {@link #LOCK_DURATION}.
 */
public final class TransactionPin {

    public static final int MAX_ATTEMPTS = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(30);

    private final UUID userId;
    private String hash;
    private int failedAttempts;
    private Instant lockedUntil;
    private Instant updatedAt;
    private final long version;

    private TransactionPin(UUID userId, String hash, int failedAttempts, Instant lockedUntil, Instant updatedAt, long version) {
        this.userId = Objects.requireNonNull(userId);
        this.hash = Objects.requireNonNull(hash);
        this.failedAttempts = failedAttempts;
        this.lockedUntil = lockedUntil;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static TransactionPin create(UUID userId, Pin pin, PinHasher hasher, Instant now) {
        return new TransactionPin(userId, hasher.hash(pin), 0, null, now, 0);
    }

    public static TransactionPin restore(UUID userId, String hash, int failedAttempts, Instant lockedUntil,
                                         Instant updatedAt, long version) {
        return new TransactionPin(userId, hash, failedAttempts, lockedUntil, updatedAt, version);
    }

    public PinVerification verify(Pin candidate, PinHasher hasher, Instant now) {
        if (isLocked(now)) {
            return new PinVerification.Locked(lockedUntil, false);
        }
        if (hasher.matches(candidate, hash)) {
            failedAttempts = 0;
            lockedUntil = null;
            updatedAt = now;
            return new PinVerification.Valid();
        }
        failedAttempts++;
        updatedAt = now;
        if (failedAttempts >= MAX_ATTEMPTS) {
            lockedUntil = now.plus(LOCK_DURATION);
            failedAttempts = 0;
            return new PinVerification.Locked(lockedUntil, true);
        }
        return new PinVerification.Incorrect(MAX_ATTEMPTS - failedAttempts);
    }

    /** Sets a new PIN (after verifying the old one, or after an OTP-confirmed reset). Clears any lock. */
    public void replace(Pin newPin, PinHasher hasher, Instant now) {
        hash = hasher.hash(newPin);
        failedAttempts = 0;
        lockedUntil = null;
        updatedAt = now;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    public UUID userId() {
        return userId;
    }

    public String hash() {
        return hash;
    }

    public int failedAttempts() {
        return failedAttempts;
    }

    public Instant lockedUntil() {
        return lockedUntil;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
