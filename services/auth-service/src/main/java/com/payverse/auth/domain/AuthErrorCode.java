package com.payverse.auth.domain;

import com.payverse.common.error.ErrorCode;

/** Error codes owned by auth-service. */
public enum AuthErrorCode implements ErrorCode {

    AUTH_INVALID_MOBILE(400, "Invalid mobile number"),
    AUTH_INVALID_DEVICE(400, "Invalid device id"),
    AUTH_OTP_INVALID(401, "Incorrect OTP"),
    AUTH_OTP_EXPIRED(401, "OTP expired or not requested"),
    AUTH_OTP_ATTEMPTS_EXCEEDED(429, "Too many incorrect OTP attempts"),
    AUTH_OTP_RATE_LIMITED(429, "Too many OTP requests"),
    AUTH_INVALID_REFRESH_TOKEN(401, "Invalid or expired refresh token"),
    AUTH_REFRESH_TOKEN_REUSED(401, "Refresh token reuse detected; all sessions on this device were revoked"),
    AUTH_INVALID_CLIENT(401, "Invalid client credentials"),
    AUTH_USER_BLOCKED(403, "User is blocked"),
    AUTH_USER_NOT_FOUND(404, "User not found"),
    AUTH_DEVICE_NOT_FOUND(404, "Device not found"),
    AUTH_PIN_INVALID_FORMAT(400, "PIN must be 4 or 6 digits"),
    AUTH_PIN_TOO_WEAK(400, "PIN is too easy to guess"),
    AUTH_PIN_ALREADY_SET(409, "Transaction PIN is already set"),
    AUTH_PIN_NOT_SET(409, "Transaction PIN is not set"),
    AUTH_PIN_INCORRECT(422, "Incorrect transaction PIN"),
    AUTH_PIN_LOCKED(423, "Transaction PIN is locked after too many failed attempts");

    private final int httpStatus;
    private final String title;

    AuthErrorCode(int httpStatus, String title) {
        this.httpStatus = httpStatus;
        this.title = title;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }

    @Override
    public String title() {
        return title;
    }
}
