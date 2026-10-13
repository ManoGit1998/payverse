package com.payverse.auth.service;

import com.payverse.auth.config.AuthProperties;
import com.payverse.auth.exception.AuthErrorCode;
import com.payverse.auth.model.MobileNumber;
import com.payverse.auth.model.OtpChallenge;
import com.payverse.auth.model.OtpPurpose;
import com.payverse.auth.repository.OtpRepository;
import com.payverse.auth.repository.RateLimitRepository;
import com.payverse.auth.security.SecureTokens;
import com.payverse.common.error.PayverseException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/** Issues and verifies one-time passwords. Codes are stored hashed, single-use and attempt-limited. */
@Service
public class OtpService {

    private final OtpRepository otpRepository;
    private final OtpSender sender;
    private final RateLimitRepository rateLimits;
    private final AuthProperties properties;
    private final Clock clock;

    public OtpService(OtpRepository otpRepository, OtpSender sender, RateLimitRepository rateLimits,
                      AuthProperties properties, Clock clock) {
        this.otpRepository = otpRepository;
        this.sender = sender;
        this.rateLimits = rateLimits;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return when the OTP expires */
    public Instant request(MobileNumber mobile, OtpPurpose purpose) {
        AuthProperties.Otp policy = properties.otp();
        if (!rateLimits.tryAcquire("otp-req:" + SecureTokens.sha256(mobile.value()), policy.maxRequests(), policy.requestWindow())) {
            throw new PayverseException(AuthErrorCode.AUTH_OTP_RATE_LIMITED,
                    "Try again in a few minutes", Map.of("retryAfterSeconds", policy.requestWindow().toSeconds()));
        }
        String code = SecureTokens.newNumericCode(policy.length());
        Instant expiresAt = clock.instant().plus(policy.ttl());
        otpRepository.save(mobile, new OtpChallenge(purpose, hash(mobile, purpose, code), 0, expiresAt));
        sender.send(mobile, code, purpose);
        return expiresAt;
    }

    /** Consumes the OTP if it matches; otherwise counts the failed attempt. */
    public void verify(MobileNumber mobile, OtpPurpose purpose, String code) {
        OtpChallenge challenge = otpRepository.find(mobile, purpose)
                .filter(c -> !c.isExpired(clock.instant()))
                .orElseThrow(() -> new PayverseException(AuthErrorCode.AUTH_OTP_EXPIRED, "Request a new OTP"));
        if (code != null && SecureTokens.constantTimeEquals(challenge.codeHash(), hash(mobile, purpose, code))) {
            otpRepository.delete(mobile, purpose);
            return;
        }
        OtpChallenge failed = challenge.withFailedAttempt();
        if (failed.attemptsExhausted()) {
            otpRepository.delete(mobile, purpose);
            throw new PayverseException(AuthErrorCode.AUTH_OTP_ATTEMPTS_EXCEEDED, "Request a new OTP");
        }
        otpRepository.save(mobile, failed);
        throw new PayverseException(AuthErrorCode.AUTH_OTP_INVALID, null,
                Map.of("remainingAttempts", OtpChallenge.MAX_ATTEMPTS - failed.attempts()));
    }

    private static String hash(MobileNumber mobile, OtpPurpose purpose, String code) {
        return SecureTokens.sha256(purpose + ":" + mobile.value() + ":" + code);
    }
}
