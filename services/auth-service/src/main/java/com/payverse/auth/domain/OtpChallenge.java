package com.payverse.auth.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * An outstanding OTP for one mobile and purpose. Only a hash of the code is kept; at most
 * {@link #MAX_ATTEMPTS} wrong guesses are allowed before the challenge is burned.
 */
public record OtpChallenge(OtpPurpose purpose, String codeHash, int attempts, Instant expiresAt) {

    public static final int MAX_ATTEMPTS = 5;

    public OtpChallenge {
        Objects.requireNonNull(purpose);
        Objects.requireNonNull(codeHash);
        Objects.requireNonNull(expiresAt);
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public OtpChallenge withFailedAttempt() {
        return new OtpChallenge(purpose, codeHash, attempts + 1, expiresAt);
    }

    public boolean attemptsExhausted() {
        return attempts >= MAX_ATTEMPTS;
    }
}
