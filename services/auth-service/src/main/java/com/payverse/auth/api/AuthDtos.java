package com.payverse.auth.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.payverse.auth.application.AuthTokens;
import com.payverse.auth.domain.Device;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request/response bodies of the auth API. Records holding secrets mask them in {@code toString}. */
final class AuthDtos {

    private AuthDtos() {
    }

    /** Whole seconds from now until {@code instant}, rounded up (so a fresh 5-minute OTP reports 300). */
    static long secondsUntil(Instant instant, Clock clock) {
        long millis = Duration.between(clock.instant(), instant).toMillis();
        return Math.max(0, (millis + 999) / 1000);
    }

    record OtpRequest(@NotBlank @Size(max = 16) @Schema(example = "9876543210") String mobile) {
    }

    record OtpResponse(Instant expiresAt, long expiresInSeconds, String message) {
    }

    record VerifyOtpRequest(
            @NotBlank @Size(max = 16) @Schema(example = "9876543210") String mobile,
            @NotBlank @Pattern(regexp = "[0-9]{4,8}") String otp,
            @NotBlank @Size(min = 8, max = 128) @Schema(example = "3f6c1d1e-device-0001") String deviceId,
            @Size(max = 100) @Schema(example = "Pixel 9") String deviceName,
            @Size(max = 32) String referralCode) {

        @Override
        public String toString() {
            return "VerifyOtpRequest[mobile=****, otp=****, deviceId=" + deviceId + "]";
        }
    }

    record RefreshRequest(@NotBlank @Size(max = 256) String refreshToken, @NotBlank @Size(min = 8, max = 128) String deviceId) {

        @Override
        public String toString() {
            return "RefreshRequest[refreshToken=****, deviceId=" + deviceId + "]";
        }
    }

    record LogoutRequest(@NotBlank @Size(max = 256) String refreshToken) {

        @Override
        public String toString() {
            return "LogoutRequest[****]";
        }
    }

    record TokenResponse(String tokenType, String accessToken, long expiresIn, String refreshToken, long refreshExpiresIn,
                         UUID userId, boolean newUser, boolean pinSet) {

        static TokenResponse of(AuthTokens tokens, boolean newUser, boolean pinSet, Clock clock) {
            return new TokenResponse("Bearer", tokens.accessToken(), secondsUntil(tokens.accessTokenExpiresAt(), clock),
                    tokens.refreshToken(), secondsUntil(tokens.refreshTokenExpiresAt(), clock), tokens.userId(), newUser, pinSet);
        }

        @Override
        public String toString() {
            return "TokenResponse[userId=" + userId + ", tokens=****]";
        }
    }

    record MeResponse(UUID userId, List<String> roles, String deviceId, boolean pinSet) {
    }

    record DeviceResponse(String deviceId, String name, Instant boundAt, Instant lastSeenAt, boolean current) {

        static DeviceResponse of(Device device, String currentDeviceId) {
            return new DeviceResponse(device.deviceId().value(), device.name(), device.boundAt(), device.lastSeenAt(),
                    device.deviceId().value().equals(currentDeviceId));
        }
    }

    record SetPinRequest(@NotBlank @Pattern(regexp = "[0-9]{4}|[0-9]{6}", message = "PIN must be 4 or 6 digits") String pin) {

        @Override
        public String toString() {
            return "SetPinRequest[****]";
        }
    }

    record ChangePinRequest(@NotBlank String currentPin,
                            @NotBlank @Pattern(regexp = "[0-9]{4}|[0-9]{6}", message = "PIN must be 4 or 6 digits") String newPin) {

        @Override
        public String toString() {
            return "ChangePinRequest[****]";
        }
    }

    record PinResetRequest(@NotBlank @Size(max = 16) String mobile) {
    }

    record PinResetConfirmRequest(@NotBlank @Size(max = 16) String mobile,
                                  @NotBlank @Pattern(regexp = "[0-9]{4,8}") String otp,
                                  @NotBlank @Pattern(regexp = "[0-9]{4}|[0-9]{6}", message = "PIN must be 4 or 6 digits") String newPin) {

        @Override
        public String toString() {
            return "PinResetConfirmRequest[****]";
        }
    }

    record VerifyPinRequest(@NotNull UUID userId, @NotBlank @Size(max = 6) String pin) {

        @Override
        public String toString() {
            return "VerifyPinRequest[userId=" + userId + ", pin=****]";
        }
    }

    record VerifyPinResponse(boolean valid) {
    }

    record UserLookupRequest(@NotBlank @Size(max = 16) String mobile) {
    }

    record UserLookupResponse(UUID userId, String mobileMasked) {
    }

    record ServiceTokenResponse(@JsonProperty("access_token") String accessToken,
                                @JsonProperty("token_type") String tokenType,
                                @JsonProperty("expires_in") long expiresIn) {

        @Override
        public String toString() {
            return "ServiceTokenResponse[****]";
        }
    }
}
