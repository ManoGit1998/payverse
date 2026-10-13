package com.payverse.auth.infrastructure.persistence;

import com.payverse.auth.application.port.RefreshTokenRepository;
import com.payverse.auth.domain.DeviceId;
import com.payverse.auth.domain.RefreshToken;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaRefreshTokenRepository implements RefreshTokenRepository {

    private final RefreshTokenJpaRepository jpa;

    JpaRefreshTokenRepository(RefreshTokenJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<RefreshToken> findByHash(String tokenHash) {
        return jpa.findByTokenHash(tokenHash).map(JpaRefreshTokenRepository::toDomain);
    }

    @Override
    public RefreshToken save(RefreshToken token) {
        RefreshTokenEntity e = jpa.findById(token.id()).orElseGet(RefreshTokenEntity::new);
        e.id = token.id();
        e.userId = token.userId();
        e.deviceId = token.deviceId().value();
        e.tokenHash = token.tokenHash();
        e.familyId = token.familyId();
        e.createdAt = token.createdAt();
        e.expiresAt = token.expiresAt();
        e.revokedAt = token.revokedAt();
        e.revokeReason = token.revokeReason() == null ? null : token.revokeReason().name();
        return toDomain(jpa.save(e));
    }

    @Override
    public int revokeFamily(UUID familyId, RefreshToken.RevokeReason reason, Instant now) {
        return jpa.revokeFamily(familyId, reason.name(), now);
    }

    @Override
    public int revokeAllForDevice(UUID userId, DeviceId deviceId, RefreshToken.RevokeReason reason, Instant now) {
        return jpa.revokeDevice(userId, deviceId.value(), reason.name(), now);
    }

    private static RefreshToken toDomain(RefreshTokenEntity e) {
        return RefreshToken.restore(e.id, e.userId, new DeviceId(e.deviceId), e.tokenHash, e.familyId, e.createdAt,
                e.expiresAt, e.revokedAt, e.revokeReason == null ? null : RefreshToken.RevokeReason.valueOf(e.revokeReason));
    }
}
