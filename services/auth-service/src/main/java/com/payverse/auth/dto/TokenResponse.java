package com.payverse.auth.dto;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record TokenResponse(String tokenType, String accessToken, long expiresIn, String refreshToken, long refreshExpiresIn,
                            UUID userId, boolean newUser, boolean pinSet) {

    public static TokenResponse of(AuthTokens tokens, boolean newUser, boolean pinSet, Clock clock) {
        return new TokenResponse("Bearer", tokens.accessToken(), secondsUntil(tokens.accessTokenExpiresAt(), clock),
                tokens.refreshToken(), secondsUntil(tokens.refreshTokenExpiresAt(), clock), tokens.userId(), newUser, pinSet);
    }

    /** Whole seconds until {@code instant}, rounded up (so a fresh 5-minute OTP reports 300). */
    public static long secondsUntil(Instant instant, Clock clock) {
        long millis = Duration.between(clock.instant(), instant).toMillis();
        return Math.max(0, (millis + 999) / 1000);
    }

    @Override
    public String toString() {
        return "TokenResponse[userId=" + userId + ", tokens=****]";
    }
}
