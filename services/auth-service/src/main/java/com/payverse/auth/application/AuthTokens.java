package com.payverse.auth.application;

import java.time.Instant;
import java.util.UUID;

/** An access + refresh token pair returned to the client. */
public record AuthTokens(UUID userId, String accessToken, Instant accessTokenExpiresAt, String refreshToken,
                         Instant refreshTokenExpiresAt) {

    @Override
    public String toString() {
        return "AuthTokens[userId=" + userId + ", tokens=****]";
    }
}
