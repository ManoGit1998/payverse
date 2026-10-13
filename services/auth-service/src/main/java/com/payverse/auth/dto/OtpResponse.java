package com.payverse.auth.dto;

import java.time.Instant;

public record OtpResponse(Instant expiresAt, long expiresInSeconds, String message) {
}
