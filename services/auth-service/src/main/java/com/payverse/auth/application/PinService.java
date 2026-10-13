package com.payverse.auth.application;

import com.payverse.auth.application.port.AuthEventPublisher;
import com.payverse.auth.application.port.TransactionPinRepository;
import com.payverse.auth.application.port.UserRepository;
import com.payverse.auth.domain.AuthErrorCode;
import com.payverse.auth.domain.MobileNumber;
import com.payverse.auth.domain.OtpPurpose;
import com.payverse.auth.domain.Pin;
import com.payverse.auth.domain.PinHasher;
import com.payverse.auth.domain.PinVerification;
import com.payverse.auth.domain.TransactionPin;
import com.payverse.auth.domain.User;
import com.payverse.common.error.PayverseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Transaction PIN lifecycle. Failed attempts must be persisted even though the call fails, so the
 * verifying methods use {@code noRollbackFor = PayverseException.class}.
 */
@Service
public class PinService {

    private final TransactionPinRepository pins;
    private final UserRepository users;
    private final PinHasher hasher;
    private final OtpService otpService;
    private final AuthEventPublisher events;
    private final Clock clock;

    public PinService(TransactionPinRepository pins, UserRepository users, PinHasher hasher, OtpService otpService,
                      AuthEventPublisher events, Clock clock) {
        this.pins = pins;
        this.users = users;
        this.hasher = hasher;
        this.otpService = otpService;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void setPin(UUID userId, String rawPin) {
        Pin pin = Pin.choose(rawPin);
        if (pins.exists(userId)) {
            throw new PayverseException(AuthErrorCode.AUTH_PIN_ALREADY_SET, "Use change or reset instead");
        }
        pins.save(TransactionPin.create(userId, pin, hasher, clock.instant()));
    }

    @Transactional(noRollbackFor = PayverseException.class)
    public void changePin(UUID userId, String currentPin, String newPin) {
        Pin next = Pin.choose(newPin);
        TransactionPin verified = check(requirePin(userId), new Pin(currentPin));
        verified.replace(next, hasher, clock.instant());
        pins.save(verified);
    }

    /** Verifies a PIN for a money movement (called by other services through /internal). */
    @Transactional(noRollbackFor = PayverseException.class)
    public void verify(UUID userId, String rawPin) {
        Pin candidate;
        try {
            candidate = new Pin(rawPin);
        } catch (PayverseException e) {
            throw new PayverseException(AuthErrorCode.AUTH_PIN_INCORRECT, null);
        }
        check(requirePin(userId), candidate);
    }

    /** Sends a PIN-reset OTP. Responds identically for unknown mobiles (no account enumeration). */
    public void requestReset(MobileNumber mobile) {
        if (users.findByMobile(mobile).isPresent()) {
            otpService.request(mobile, OtpPurpose.PIN_RESET);
        }
    }

    @Transactional
    public void confirmReset(MobileNumber mobile, String otp, String newPin) {
        Pin next = Pin.choose(newPin);
        otpService.verify(mobile, OtpPurpose.PIN_RESET, otp);
        User user = users.findByMobile(mobile)
                .orElseThrow(() -> new PayverseException(AuthErrorCode.AUTH_OTP_EXPIRED, "Request a new OTP"));
        user.ensureActive();
        Instant now = clock.instant();
        TransactionPin pin = pins.find(user.id())
                .map(existing -> {
                    existing.replace(next, hasher, now);
                    return existing;
                })
                .orElseGet(() -> TransactionPin.create(user.id(), next, hasher, now));
        pins.save(pin);
    }

    public boolean isSet(UUID userId) {
        return pins.exists(userId);
    }

    private TransactionPin requirePin(UUID userId) {
        return pins.find(userId).orElseThrow(() -> new PayverseException(AuthErrorCode.AUTH_PIN_NOT_SET, "Set a PIN first"));
    }

    /** Verifies and persists the attempt; returns the saved PIN (fresh version) when valid. */
    private TransactionPin check(TransactionPin pin, Pin candidate) {
        PinVerification result = pin.verify(candidate, hasher, clock.instant());
        TransactionPin saved = pins.save(pin);
        switch (result) {
            case PinVerification.Valid valid -> {
                return saved;
            }
            case PinVerification.Incorrect incorrect -> throw new PayverseException(AuthErrorCode.AUTH_PIN_INCORRECT, null,
                    Map.of("remainingAttempts", incorrect.remainingAttempts()));
            case PinVerification.Locked locked -> {
                if (locked.justLocked()) {
                    events.pinLocked(pin.userId(), locked.until());
                }
                throw new PayverseException(AuthErrorCode.AUTH_PIN_LOCKED, null,
                        Map.of("lockedUntil", locked.until().toString()));
            }
        }
    }
}
