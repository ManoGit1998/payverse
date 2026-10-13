package com.payverse.auth.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Opaque refresh token (only its SHA-256 hash is stored). Tokens rotate on every use; all tokens
 * descending from one login share a {@code familyId}, so presenting an already-rotated token revokes
 * the whole family (stolen-token detection).
 */
public final class RefreshToken {

    public enum RevokeReason { ROTATED, LOGOUT, REUSE_DETECTED, DEVICE_REVOKED }

    private final UUID id;
    private final UUID userId;
    private final DeviceId deviceId;
    private final String tokenHash;
    private final UUID familyId;
    private final Instant createdAt;
    private final Instant expiresAt;
    private Instant revokedAt;
    private RevokeReason revokeReason;

    private RefreshToken(UUID id, UUID userId, DeviceId deviceId, String tokenHash, UUID familyId, Instant createdAt,
                         Instant expiresAt, Instant revokedAt, RevokeReason revokeReason) {
        this.id = Objects.requireNonNull(id);
        this.userId = Objects.requireNonNull(userId);
        this.deviceId = Objects.requireNonNull(deviceId);
        this.tokenHash = Objects.requireNonNull(tokenHash);
        this.familyId = Objects.requireNonNull(familyId);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.revokedAt = revokedAt;
        this.revokeReason = revokeReason;
    }

    public static RefreshToken issue(UUID userId, DeviceId deviceId, String tokenHash, Instant now, Duration ttl) {
        return new RefreshToken(UUID.randomUUID(), userId, deviceId, tokenHash, UUID.randomUUID(), now, now.plus(ttl), null, null);
    }

    public static RefreshToken restore(UUID id, UUID userId, DeviceId deviceId, String tokenHash, UUID familyId,
                                       Instant createdAt, Instant expiresAt, Instant revokedAt, RevokeReason revokeReason) {
        return new RefreshToken(id, userId, deviceId, tokenHash, familyId, createdAt, expiresAt, revokedAt, revokeReason);
    }

    /** Revokes this token and returns its successor in the same family (same absolute lifetime window). */
    public RefreshToken rotate(String newTokenHash, Instant now, Duration ttl) {
        revoke(RevokeReason.ROTATED, now);
        return new RefreshToken(UUID.randomUUID(), userId, deviceId, newTokenHash, familyId, now, now.plus(ttl), null, null);
    }

    public void revoke(RevokeReason reason, Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
            revokeReason = reason;
        }
    }

    public boolean isUsable(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    /** A rotated token presented again means someone else holds a copy. */
    public boolean wasRotated() {
        return revokeReason == RevokeReason.ROTATED;
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public DeviceId deviceId() {
        return deviceId;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public UUID familyId() {
        return familyId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public RevokeReason revokeReason() {
        return revokeReason;
    }
}
