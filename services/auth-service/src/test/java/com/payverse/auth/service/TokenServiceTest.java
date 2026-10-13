package com.payverse.auth.service;

import com.payverse.auth.dto.AuthTokens;
import com.payverse.auth.entity.RefreshToken;
import com.payverse.auth.entity.RevokeReason;
import com.payverse.auth.entity.User;
import com.payverse.auth.exception.AuthErrorCode;
import com.payverse.auth.model.DeviceId;
import com.payverse.auth.model.MobileNumber;
import com.payverse.auth.repository.RefreshTokenRepository;
import com.payverse.auth.repository.UserRepository;
import com.payverse.auth.security.JwtTokenProvider;
import com.payverse.auth.security.SecureTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.payverse.auth.service.ServiceTestSupport.CLOCK;
import static com.payverse.auth.service.ServiceTestSupport.PROPERTIES;
import static com.payverse.auth.service.ServiceTestSupport.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenServiceTest {

    static final DeviceId DEVICE = new DeviceId("device-0001");

    final Map<String, RefreshToken> byHash = new HashMap<>();
    final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    final UserRepository users = mock(UserRepository.class);
    final JwtTokenProvider jwt = mock(JwtTokenProvider.class);
    final User user = User.register(new MobileNumber("9876543210"), "h", Set.of(), CLOCK.instant());
    TokenService service;

    @BeforeEach
    void setUp() {
        when(refreshTokens.save(any())).thenAnswer(inv -> {
            RefreshToken token = inv.getArgument(0);
            byHash.put(token.getTokenHash(), token);
            return token;
        });
        when(refreshTokens.findByTokenHash(anyString())).thenAnswer(inv -> Optional.ofNullable(byHash.get(inv.getArgument(0))));
        when(refreshTokens.revokeFamily(any(), any(), any())).thenAnswer(inv -> {
            byHash.values().stream().filter(t -> t.getFamilyId().equals(inv.getArgument(0)))
                    .forEach(t -> t.revoke(inv.getArgument(1), inv.getArgument(2)));
            return 1;
        });
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(jwt.issue(anyString(), any(), anyString(), any())).thenReturn(
                new JwtTokenProvider.IssuedToken("jwt", Instant.parse("2026-10-14T10:15:00Z")));
        service = new TokenService(jwt, refreshTokens, users, PROPERTIES, CLOCK);
    }

    @Test
    void refreshRotatesAndReuseRevokesTheFamily() {
        AuthTokens first = service.issueFor(user, DEVICE);
        assertThat(byHash).containsKey(SecureTokens.sha256(first.refreshToken()));
        AuthTokens second = service.refresh(first.refreshToken(), DEVICE);
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());

        assertError(() -> service.refresh(first.refreshToken(), DEVICE), AuthErrorCode.AUTH_REFRESH_TOKEN_REUSED);
        verify(refreshTokens).revokeFamily(any(), eq(RevokeReason.REUSE_DETECTED), any());
        assertError(() -> service.refresh(second.refreshToken(), DEVICE), AuthErrorCode.AUTH_INVALID_REFRESH_TOKEN);
    }

    @Test
    void refreshIsBoundToDeviceAndActiveUser() {
        AuthTokens tokens = service.issueFor(user, DEVICE);
        assertError(() -> service.refresh(tokens.refreshToken(), new DeviceId("device-other")), AuthErrorCode.AUTH_INVALID_REFRESH_TOKEN);
        assertError(() -> service.refresh("unknown", DEVICE), AuthErrorCode.AUTH_INVALID_REFRESH_TOKEN);
        user.block();
        assertError(() -> service.refresh(tokens.refreshToken(), DEVICE), AuthErrorCode.AUTH_USER_BLOCKED);
    }

    @Test
    void logoutRevokesOnlyOwnSessions() {
        AuthTokens tokens = service.issueFor(user, DEVICE);
        service.logout(java.util.UUID.randomUUID(), tokens.refreshToken());
        assertThat(byHash.get(SecureTokens.sha256(tokens.refreshToken())).getRevokedAt()).isNull();
        service.logout(user.getId(), tokens.refreshToken());
        assertError(() -> service.refresh(tokens.refreshToken(), DEVICE), AuthErrorCode.AUTH_INVALID_REFRESH_TOKEN);
    }
}
