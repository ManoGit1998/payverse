package com.payverse.auth.service;

import com.payverse.auth.config.AuthProperties;
import com.payverse.auth.dto.AuthTokens;
import com.payverse.auth.entity.RefreshToken;
import com.payverse.auth.entity.RevokeReason;
import com.payverse.auth.entity.Role;
import com.payverse.auth.entity.User;
import com.payverse.auth.exception.AuthErrorCode;
import com.payverse.auth.model.DeviceId;
import com.payverse.auth.repository.RefreshTokenRepository;
import com.payverse.auth.repository.UserRepository;
import com.payverse.auth.security.JwtTokenProvider;
import com.payverse.auth.security.SecureTokens;
import com.payverse.common.error.PayverseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Access-token issuance and refresh-token rotation with reuse detection. */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokens;
    private final UserRepository users;
    private final AuthProperties properties;
    private final Clock clock;

    public TokenService(JwtTokenProvider jwtTokenProvider, RefreshTokenRepository refreshTokens, UserRepository users,
                        AuthProperties properties, Clock clock) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.properties = properties;
        this.clock = clock;
    }

    /** Starts a new token family for a fresh login. Joins the caller's transaction. */
    @Transactional
    public AuthTokens issueFor(User user, DeviceId deviceId) {
        String raw = SecureTokens.newOpaqueToken();
        RefreshToken refresh = refreshTokens.save(RefreshToken.issue(user.getId(), deviceId, SecureTokens.sha256(raw),
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
        RefreshToken current = refreshTokens.findByTokenHash(SecureTokens.sha256(rawRefreshToken))
                .orElseThrow(() -> new PayverseException(AuthErrorCode.AUTH_INVALID_REFRESH_TOKEN, null));
        if (current.wasRotated()) {
            int revoked = refreshTokens.revokeFamily(current.getFamilyId(), RevokeReason.REUSE_DETECTED, now);
            log.warn("Refresh token reuse detected for user {} (family {}, {} tokens revoked)",
                    current.getUserId(), current.getFamilyId(), revoked);
            throw new PayverseException(AuthErrorCode.AUTH_REFRESH_TOKEN_REUSED, null);
        }
        if (!current.isUsable(now) || !current.isBoundTo(deviceId)) {
            throw new PayverseException(AuthErrorCode.AUTH_INVALID_REFRESH_TOKEN, null);
        }
        User user = users.findById(current.getUserId())
                .orElseThrow(() -> new PayverseException(AuthErrorCode.AUTH_INVALID_REFRESH_TOKEN, null));
        user.ensureActive();

        String raw = SecureTokens.newOpaqueToken();
        RefreshToken next = refreshTokens.save(current.rotate(SecureTokens.sha256(raw), now, properties.refreshTokenTtl()));
        return tokens(user, deviceId, raw, next);
    }

    /** Ends the session the refresh token belongs to (its whole family). */
    @Transactional
    public void logout(UUID userId, String rawRefreshToken) {
        refreshTokens.findByTokenHash(SecureTokens.sha256(rawRefreshToken))
                .filter(token -> token.getUserId().equals(userId))
                .ifPresent(token -> refreshTokens.revokeFamily(token.getFamilyId(), RevokeReason.LOGOUT, clock.instant()));
    }

    private AuthTokens tokens(User user, DeviceId deviceId, String rawRefresh, RefreshToken refresh) {
        Set<String> roles = user.getRoleSet().stream().map(Role::name).collect(Collectors.toUnmodifiableSet());
        JwtTokenProvider.IssuedToken access = jwtTokenProvider.issue(user.getId().toString(), roles, deviceId.value(),
                properties.accessTokenTtl());
        return new AuthTokens(user.getId(), access.value(), access.expiresAt(), rawRefresh, refresh.getExpiresAt());
    }
}
