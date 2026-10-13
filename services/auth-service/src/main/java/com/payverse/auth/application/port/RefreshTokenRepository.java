package com.payverse.auth.application.port;

import com.payverse.auth.domain.DeviceId;
import com.payverse.auth.domain.RefreshToken;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository {

    Optional<RefreshToken> findByHash(String tokenHash);

    RefreshToken save(RefreshToken token);

    int revokeFamily(UUID familyId, RefreshToken.RevokeReason reason, Instant now);

    int revokeAllForDevice(UUID userId, DeviceId deviceId, RefreshToken.RevokeReason reason, Instant now);
}
