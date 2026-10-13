package com.payverse.auth.application;

import com.payverse.auth.application.port.AccessTokenIssuer;
import com.payverse.auth.application.port.RefreshTokenRepository;
import com.payverse.auth.application.port.UserRepository;
import com.payverse.auth.domain.AuthErrorCode;
import com.payverse.auth.domain.DeviceId;
import com.payverse.auth.domain.RefreshToken;
import com.payverse.auth.domain.Role;
import com.payverse.auth.domain.User;
import com.payverse.common.error.PayverseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Access-token issuance and refresh-token rotation with reuse detection. */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private final AccessTokenIssuer issuer;
    private final RefreshTokenRepository refreshTokens;
    private final UserRepository users;
    private final AuthProperties properties;
    private final Clock clock;

    public TokenService(AccessTokenIssuer issuer, RefreshTokenRepository refreshTokens, UserRepository users,
                        AuthProperties properties, Clock clock) {
        this.issuer = issuer;
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.properties = properties;
        this.clock = clock;
    }

    /** Starts a new token family for a fresh login. Joins the caller's transaction. */
    @Transactional
    public AuthTokens issueFor(User user, DeviceId deviceId) {
        String raw = SecureTokens.newOpaqueToken();
        RefreshToken refresh = refreshTokens.save(RefreshToken.issue(user.id(), deviceId, SecureTokens.sha256(raw),
                clock.instant(), properties.refreshTokenTtl()));
        return tokens(user, deviceId, raw, refresh);
    }

    /**
     * Rotates a refresh token. Presenting an already-rotated token revokes the whole family; that
     * revocation must commit even though the call fails, hence {@code noRollbackFor}.
     */
    @Transactional(noRollbackFor = PayverseException.class)
    public AuthTokens refresh(String rawRefreshToken, DeviceId deviceId) {
        Instant now = clock.instant();
        RefreshToken current = refreshTokens.findByHash(SecureTokens.sha256(rawRefreshToken))
                .orElseThrow(() -> new PayverseException(AuthErrorCode.AUTH_INVALID_REFRESH_TOKEN, null));
        if (current.wasRotated()) {
            int revoked = refreshTokens.revokeFamily(current.familyId(), RefreshToken.RevokeReason.REUSE_DETECTED, now);
            log.warn("Refresh token reuse detected for user {} (family {}, {} tokens revoked)",
                    current.userId(), current.familyId(), revoked);
            throw new PayverseException(AuthErrorCode.AUTH_REFRESH_TOKEN_REUSED, null);
        }
        if (!current.isUsable(now) || !current.deviceId().equals(deviceId)) {
            throw new PayverseException(AuthErrorCode.AUTH_INVALID_REFRESH_TOKEN, null);
        }
        User user = users.findById(current.userId())
                .orElseThrow(() -> new PayverseException(AuthErrorCode.AUTH_INVALID_REFRESH_TOKEN, null));
        user.ensureActive();

        String raw = SecureTokens.newOpaqueToken();
        RefreshToken next = current.rotate(SecureTokens.sha256(raw), now, properties.refreshTokenTtl());
        refreshTokens.save(current);
        refreshTokens.save(next);
        return tokens(user, deviceId, raw, next);
    }

    /** Ends the session the refresh token belongs to (its whole family). */
    @Transactional
    public void logout(UUID userId, String rawRefreshToken) {
        refreshTokens.findByHash(SecureTokens.sha256(rawRefreshToken))
                .filter(token -> token.userId().equals(userId))
                .ifPresent(token -> refreshTokens.revokeFamily(token.familyId(), RefreshToken.RevokeReason.LOGOUT, clock.instant()));
    }

    private AuthTokens tokens(User user, DeviceId deviceId, String rawRefresh, RefreshToken refresh) {
        Set<String> roles = user.roles().stream().map(Role::name).collect(Collectors.toUnmodifiableSet());
        AccessTokenIssuer.IssuedToken access = issuer.issue(user.id().toString(), roles, deviceId.value(), properties.accessTokenTtl());
        return new AuthTokens(user.id(), access.value(), access.expiresAt(), rawRefresh, refresh.expiresAt());
    }
}
