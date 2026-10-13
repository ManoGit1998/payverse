package com.payverse.auth.application;

import com.payverse.auth.domain.AuthErrorCode;
import com.payverse.auth.domain.DeviceId;
import com.payverse.auth.domain.MobileNumber;
import com.payverse.auth.domain.OtpPurpose;
import com.payverse.auth.domain.Pin;
import com.payverse.auth.domain.PinHasher;
import com.payverse.auth.domain.Role;
import com.payverse.common.error.ErrorCode;
import com.payverse.common.error.PayverseException;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthApplicationTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T10:00:00Z"), ZoneOffset.UTC);
    static final PinHasher HASHER = new PinHasher() {
        @Override
        public String hash(Pin pin) {
            return "hash:" + pin.value();
        }

        @Override
        public boolean matches(Pin pin, String hash) {
            return hash.equals("hash:" + pin.value());
        }
    };
    static final String MOBILE = "9876543210";
    static final String ADMIN_MOBILE = "9000000001";
    static final DeviceId DEVICE = new DeviceId("device-0001");

    InMemoryAdapters.Users users;
    InMemoryAdapters.Devices devices;
    InMemoryAdapters.RefreshTokens refreshTokens;
    InMemoryAdapters.Pins pins;
    InMemoryAdapters.Otps otps;
    InMemoryAdapters.CapturingSender sender;
    InMemoryAdapters.RecordingEvents events;
    AuthProperties properties;
    OtpService otpService;
    TokenService tokenService;
    LoginService loginService;
    PinService pinService;
    DeviceService deviceService;

    @BeforeEach
    void setUp() {
        users = new InMemoryAdapters.Users();
        devices = new InMemoryAdapters.Devices();
        refreshTokens = new InMemoryAdapters.RefreshTokens();
        pins = new InMemoryAdapters.Pins();
        otps = new InMemoryAdapters.Otps();
        sender = new InMemoryAdapters.CapturingSender();
        events = new InMemoryAdapters.RecordingEvents();
        properties = new AuthProperties(null, null, null, null, List.of(ADMIN_MOBILE), List.of(),
                List.of(new AuthProperties.ServiceClient("wallet-service", "s3cret")), null);
        otpService = new OtpService(otps, sender, new InMemoryAdapters.CountingLimiter(), properties, CLOCK);
        tokenService = new TokenService(new InMemoryAdapters.FakeIssuer(), refreshTokens, users, properties, CLOCK);
        loginService = new LoginService(otpService, users, devices, pins, tokenService, events, properties, CLOCK);
        pinService = new PinService(pins, users, HASHER, otpService, events, CLOCK);
        deviceService = new DeviceService(devices, refreshTokens, CLOCK);
    }

    LoginService.LoginResult login(String mobile, DeviceId device) {
        otpService.request(MobileNumber.parse(mobile), OtpPurpose.LOGIN);
        String code = sender.code(mobile, OtpPurpose.LOGIN);
        return loginService.login(new LoginService.LoginCommand(MobileNumber.parse(mobile), code, device, "Pixel", null, "127.0.0.1"));
    }

    static void assertError(ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    @Nested
    class Otp {

        @Test
        void codeIsSingleUse() {
            MobileNumber mobile = MobileNumber.parse(MOBILE);
            otpService.request(mobile, OtpPurpose.LOGIN);
            String code = sender.code(MOBILE, OtpPurpose.LOGIN);
            assertThat(code).hasSize(6).containsOnlyDigits();
            otpService.verify(mobile, OtpPurpose.LOGIN, code);
            assertError(() -> otpService.verify(mobile, OtpPurpose.LOGIN, code), AuthErrorCode.AUTH_OTP_EXPIRED);
        }

        @Test
        void wrongCodesBurnTheChallengeAfterFiveAttempts() {
            MobileNumber mobile = MobileNumber.parse(MOBILE);
            otpService.request(mobile, OtpPurpose.LOGIN);
            String code = sender.code(MOBILE, OtpPurpose.LOGIN);
            String wrong = code.equals("000000") ? "111111" : "000000";
            for (int i = 0; i < 4; i++) {
                assertError(() -> otpService.verify(mobile, OtpPurpose.LOGIN, wrong), AuthErrorCode.AUTH_OTP_INVALID);
            }
            assertError(() -> otpService.verify(mobile, OtpPurpose.LOGIN, wrong), AuthErrorCode.AUTH_OTP_ATTEMPTS_EXCEEDED);
            assertError(() -> otpService.verify(mobile, OtpPurpose.LOGIN, code), AuthErrorCode.AUTH_OTP_EXPIRED);
        }

        @Test
        void purposesAreIsolated() {
            MobileNumber mobile = MobileNumber.parse(MOBILE);
            otpService.request(mobile, OtpPurpose.PIN_RESET);
            String code = sender.code(MOBILE, OtpPurpose.PIN_RESET);
            assertError(() -> otpService.verify(mobile, OtpPurpose.LOGIN, code), AuthErrorCode.AUTH_OTP_EXPIRED);
        }

        @Test
        void requestsAreRateLimitedPerMobile() {
            MobileNumber mobile = MobileNumber.parse(MOBILE);
            for (int i = 0; i < 3; i++) {
                otpService.request(mobile, OtpPurpose.LOGIN);
            }
            assertError(() -> otpService.request(mobile, OtpPurpose.LOGIN), AuthErrorCode.AUTH_OTP_RATE_LIMITED);
        }
    }

    @Nested
    class Login {

        @Test
        void firstLoginRegistersUserAndPublishesEvent() {
            LoginService.LoginResult result = login(MOBILE, DEVICE);
            assertThat(result.newUser()).isTrue();
            assertThat(result.pinSet()).isFalse();
            assertThat(events.events).containsExactly("registered:" + result.tokens().userId());
            assertThat(devices.devices).hasSize(1);
        }

        @Test
        void loginFromNewDevicePublishesNewDeviceEvent() {
            login(MOBILE, DEVICE);
            LoginService.LoginResult second = login(MOBILE, new DeviceId("device-0002"));
            assertThat(second.newUser()).isFalse();
            assertThat(events.events).last().isEqualTo("new-device:device-0002");
            login(MOBILE, DEVICE);
            assertThat(events.events).hasSize(2);
        }

        @Test
        void configuredAdminMobilesGetAdminRole() {
            LoginService.LoginResult result = login(ADMIN_MOBILE, DEVICE);
            assertThat(users.findById(result.tokens().userId()).orElseThrow().roles()).contains(Role.ADMIN, Role.USER);
        }

        @Test
        void blockedUsersCannotLogIn() {
            UUID userId = login(MOBILE, DEVICE).tokens().userId();
            users.findById(userId).orElseThrow().block();
            assertError(() -> login(MOBILE, DEVICE), AuthErrorCode.AUTH_USER_BLOCKED);
        }
    }

    @Nested
    class Tokens {

        @Test
        void refreshRotatesAndReuseRevokesTheFamily() {
            AuthTokens first = login(MOBILE, DEVICE).tokens();
            AuthTokens second = tokenService.refresh(first.refreshToken(), DEVICE);
            assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());

            assertError(() -> tokenService.refresh(first.refreshToken(), DEVICE), AuthErrorCode.AUTH_REFRESH_TOKEN_REUSED);
            assertError(() -> tokenService.refresh(second.refreshToken(), DEVICE), AuthErrorCode.AUTH_INVALID_REFRESH_TOKEN);
        }

        @Test
        void refreshIsBoundToTheDevice() {
            AuthTokens tokens = login(MOBILE, DEVICE).tokens();
            assertError(() -> tokenService.refresh(tokens.refreshToken(), new DeviceId("device-other")), AuthErrorCode.AUTH_INVALID_REFRESH_TOKEN);
            assertError(() -> tokenService.refresh("unknown-token", DEVICE), AuthErrorCode.AUTH_INVALID_REFRESH_TOKEN);
        }

        @Test
        void logoutRevokesOnlyOwnSessions() {
            AuthTokens tokens = login(MOBILE, DEVICE).tokens();
            tokenService.logout(UUID.randomUUID(), tokens.refreshToken());
            AuthTokens rotated = tokenService.refresh(tokens.refreshToken(), DEVICE);
            tokenService.logout(tokens.userId(), rotated.refreshToken());
            assertError(() -> tokenService.refresh(rotated.refreshToken(), DEVICE), AuthErrorCode.AUTH_INVALID_REFRESH_TOKEN);
        }

        @Test
        void revokingADeviceKillsItsSessions() {
            AuthTokens tokens = login(MOBILE, DEVICE).tokens();
            assertThat(deviceService.activeDevices(tokens.userId())).hasSize(1);
            deviceService.revoke(tokens.userId(), DEVICE);
            assertThat(deviceService.activeDevices(tokens.userId())).isEmpty();
            assertError(() -> tokenService.refresh(tokens.refreshToken(), DEVICE), AuthErrorCode.AUTH_INVALID_REFRESH_TOKEN);
            assertError(() -> deviceService.revoke(tokens.userId(), DEVICE), AuthErrorCode.AUTH_DEVICE_NOT_FOUND);
        }
    }

    @Nested
    class Pins {

        UUID userId;

        @BeforeEach
        void loggedIn() {
            userId = login(MOBILE, DEVICE).tokens().userId();
        }

        @Test
        void setOnceThenVerify() {
            pinService.setPin(userId, "4826");
            assertThat(pinService.isSet(userId)).isTrue();
            assertError(() -> pinService.setPin(userId, "7391"), AuthErrorCode.AUTH_PIN_ALREADY_SET);
            pinService.verify(userId, "4826");
            assertError(() -> pinService.verify(userId, "1111"), AuthErrorCode.AUTH_PIN_INCORRECT);
            assertError(() -> pinService.verify(userId, "abc"), AuthErrorCode.AUTH_PIN_INCORRECT);
        }

        @Test
        void verifyWithoutPinFails() {
            assertError(() -> pinService.verify(userId, "4826"), AuthErrorCode.AUTH_PIN_NOT_SET);
        }

        @Test
        void lockoutPublishesEventOnce() {
            pinService.setPin(userId, "4826");
            for (int i = 0; i < 4; i++) {
                assertError(() -> pinService.verify(userId, "1111"), AuthErrorCode.AUTH_PIN_INCORRECT);
            }
            assertError(() -> pinService.verify(userId, "1111"), AuthErrorCode.AUTH_PIN_LOCKED);
            assertError(() -> pinService.verify(userId, "4826"), AuthErrorCode.AUTH_PIN_LOCKED);
            assertThat(events.events.stream().filter(e -> e.startsWith("pin-locked")).count()).isEqualTo(1);
        }

        @Test
        void changeRequiresCurrentPin() {
            pinService.setPin(userId, "4826");
            assertError(() -> pinService.changePin(userId, "1111", "7391"), AuthErrorCode.AUTH_PIN_INCORRECT);
            pinService.changePin(userId, "4826", "7391");
            pinService.verify(userId, "7391");
            assertError(() -> pinService.changePin(userId, "7391", "1234"), AuthErrorCode.AUTH_PIN_TOO_WEAK);
        }

        @Test
        void resetWithOtpClearsLock() {
            pinService.setPin(userId, "4826");
            for (int i = 0; i < 5; i++) {
                assertThatThrownBy(() -> pinService.verify(userId, "1111")).isInstanceOf(PayverseException.class);
            }
            pinService.requestReset(MobileNumber.parse(MOBILE));
            String code = sender.code(MOBILE, OtpPurpose.PIN_RESET);
            pinService.confirmReset(MobileNumber.parse(MOBILE), code, "7391");
            pinService.verify(userId, "7391");
        }

        @Test
        void resetRequestForUnknownMobileSendsNothing() {
            pinService.requestReset(MobileNumber.parse("9123456789"));
            assertThat(sender.code("9123456789", OtpPurpose.PIN_RESET)).isNull();
        }
    }

    @Nested
    class ServiceClients {

        @Test
        void issuesServiceTokensForValidCredentialsOnly() {
            ServiceClientService service = new ServiceClientService(new InMemoryAdapters.FakeIssuer(), properties);
            assertThat(service.issue("wallet-service", "s3cret").value()).isEqualTo("jwt-for-wallet-service-1");
            assertError(() -> service.issue("wallet-service", "wrong"), AuthErrorCode.AUTH_INVALID_CLIENT);
            assertError(() -> service.issue("unknown", "s3cret"), AuthErrorCode.AUTH_INVALID_CLIENT);
        }
    }

    @Test
    void secureTokensHelpers() {
        assertThat(SecureTokens.newOpaqueToken()).hasSize(43).isNotEqualTo(SecureTokens.newOpaqueToken());
        assertThat(SecureTokens.sha256("a")).hasSize(64);
        assertThat(SecureTokens.constantTimeEquals("a", "a")).isTrue();
        assertThat(SecureTokens.constantTimeEquals("a", null)).isFalse();
    }
}
