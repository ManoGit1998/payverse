package com.payverse.auth.application;

import com.payverse.auth.application.port.OtpSender;
import com.payverse.auth.application.port.OtpStore;
import com.payverse.auth.application.port.RequestRateLimiter;
import com.payverse.auth.domain.AuthErrorCode;
import com.payverse.auth.domain.MobileNumber;
import com.payverse.auth.domain.OtpChallenge;
import com.payverse.auth.domain.OtpPurpose;
import com.payverse.common.error.PayverseException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/** Issues and verifies one-time passwords. Codes are stored hashed, single-use and attempt-limited. */
@Service
public class OtpService {

    private final OtpStore store;
    private final OtpSender sender;
    private final RequestRateLimiter rateLimiter;
    private final AuthProperties properties;
    private final Clock clock;

    public OtpService(OtpStore store, OtpSender sender, RequestRateLimiter rateLimiter, AuthProperties properties, Clock clock) {
        this.store = store;
        this.sender = sender;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.clock = clock;
    }

    public record OtpIssued(Instant expiresAt) {
    }

    public OtpIssued request(MobileNumber mobile, OtpPurpose purpose) {
        AuthProperties.Otp policy = properties.otp();
        String limitKey = "otp-req:" + SecureTokens.sha256(mobile.value());
        if (!rateLimiter.tryAcquire(limitKey, policy.maxRequests(), policy.requestWindow())) {
            throw new PayverseException(AuthErrorCode.AUTH_OTP_RATE_LIMITED,
                    "Try again in a few minutes", Map.of("retryAfterSeconds", policy.requestWindow().toSeconds()));
        }
        String code = SecureTokens.newNumericCode(policy.length());
        Instant expiresAt = clock.instant().plus(policy.ttl());
        store.save(mobile, new OtpChallenge(purpose, hash(mobile, purpose, code), 0, expiresAt));
        sender.send(mobile, code, purpose);
        return new OtpIssued(expiresAt);
    }

    /** Consumes the OTP if it matches; otherwise counts the failed attempt. */
    public void verify(MobileNumber mobile, OtpPurpose purpose, String code) {
        OtpChallenge challenge = store.find(mobile, purpose)
                .filter(c -> !c.isExpired(clock.instant()))
                .orElseThrow(() -> new PayverseException(AuthErrorCode.AUTH_OTP_EXPIRED, "Request a new OTP"));
        if (code != null && SecureTokens.constantTimeEquals(challenge.codeHash(), hash(mobile, purpose, code))) {
            store.delete(mobile, purpose);
            return;
        }
        OtpChallenge failed = challenge.withFailedAttempt();
        if (failed.attemptsExhausted()) {
            store.delete(mobile, purpose);
            throw new PayverseException(AuthErrorCode.AUTH_OTP_ATTEMPTS_EXCEEDED, "Request a new OTP");
        }
        store.save(mobile, failed);
        throw new PayverseException(AuthErrorCode.AUTH_OTP_INVALID, null,
                Map.of("remainingAttempts", OtpChallenge.MAX_ATTEMPTS - failed.attempts()));
    }

    private static String hash(MobileNumber mobile, OtpPurpose purpose, String code) {
        return SecureTokens.sha256(purpose + ":" + mobile.value() + ":" + code);
    }
}
