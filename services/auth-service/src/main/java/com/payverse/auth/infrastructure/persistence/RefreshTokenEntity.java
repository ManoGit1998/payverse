package com.payverse.auth.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
class RefreshTokenEntity {

    @Id
    UUID id;

    @Column(name = "user_id", nullable = false)
    UUID userId;

    @Column(name = "device_id", nullable = false)
    String deviceId;

    @Column(name = "token_hash", nullable = false, unique = true)
    String tokenHash;

    @Column(name = "family_id", nullable = false)
    UUID familyId;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    Instant expiresAt;

    @Column(name = "revoked_at")
    Instant revokedAt;

    @Column(name = "revoke_reason")
    String revokeReason;

    protected RefreshTokenEntity() {
    }
}
