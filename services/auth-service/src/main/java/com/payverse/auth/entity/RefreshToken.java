package com.payverse.auth.entity;

import com.payverse.auth.model.DeviceId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Opaque refresh token (only its SHA-256 hash is stored). Tokens rotate on every use; all tokens
 * descending from one login share a {@code familyId}, so presenting an already-rotated token revokes
 * the whole family (stolen-token detection).
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_reason")
    private RevokeReason revokeReason;

    public static RefreshToken issue(UUID userId, DeviceId deviceId, String tokenHash, Instant now, Duration ttl) {
        return create(userId, deviceId.value(), tokenHash, UUID.randomUUID(), now, ttl);
    }

    /** Revokes this token and returns its successor in the same family. */
    public RefreshToken rotate(String newTokenHash, Instant now, Duration ttl) {
        revoke(RevokeReason.ROTATED, now);
        return create(userId, deviceId, newTokenHash, familyId, now, ttl);
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

    public boolean isBoundTo(DeviceId device) {
        return deviceId.equals(device.value());
    }

    /** A rotated token presented again means someone else holds a copy. */
    public boolean wasRotated() {
        return revokeReason == RevokeReason.ROTATED;
    }

    private static RefreshToken create(UUID userId, String deviceId, String hash, UUID familyId, Instant now, Duration ttl) {
        RefreshToken token = new RefreshToken();
        token.id = UUID.randomUUID();
        token.userId = userId;
        token.deviceId = deviceId;
        token.tokenHash = hash;
        token.familyId = familyId;
        token.createdAt = now;
        token.expiresAt = now.plus(ttl);
        return token;
    }
}
