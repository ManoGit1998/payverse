package com.payverse.auth.dto;

import java.time.Instant;
import java.util.UUID;

/** Access + refresh token pair produced by the token service. */
public record AuthTokens(UUID userId, String accessToken, Instant accessTokenExpiresAt, String refreshToken,
                         Instant refreshTokenExpiresAt) {

    @Override
    public String toString() {
        return "AuthTokens[userId=" + userId + ", tokens=****]";
    }
}
