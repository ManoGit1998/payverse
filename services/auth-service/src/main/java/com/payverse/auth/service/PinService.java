package com.payverse.auth.service;

import com.payverse.auth.entity.TransactionPin;
import com.payverse.auth.entity.User;
import com.payverse.auth.exception.AuthErrorCode;
import com.payverse.auth.model.MobileNumber;
import com.payverse.auth.model.OtpPurpose;
import com.payverse.auth.model.Pin;
import com.payverse.auth.model.PinVerification;
import com.payverse.auth.repository.TransactionPinRepository;
import com.payverse.auth.repository.UserRepository;
import com.payverse.auth.security.PinEncoder;
import com.payverse.common.error.PayverseException;
import com.payverse.common.web.crypto.BlindIndexer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Transaction PIN lifecycle. Failed attempts must be persisted even though the call fails, so the
 * verifying methods use {@code noRollbackFor = PayverseException.class}.
 */
@Service
public class PinService {

    private final TransactionPinRepository pins;
    private final UserRepository users;
    private final PinEncoder pinEncoder;
    private final OtpService otpService;
    private final AuthEventPublisher events;
    private final BlindIndexer blindIndexer;
    private final Clock clock;

    public PinService(TransactionPinRepository pins, UserRepository users, PinEncoder pinEncoder, OtpService otpService,
                      AuthEventPublisher events, BlindIndexer blindIndexer, Clock clock) {
        this.pins = pins;
        this.users = users;
        this.pinEncoder = pinEncoder;
        this.otpService = otpService;
        this.events = events;
        this.blindIndexer = blindIndexer;
        this.clock = clock;
    }

    @Transactional
    public void setPin(UUID userId, String rawPin) {
        Pin pin = Pin.choose(rawPin);
        if (pins.existsById(userId)) {
            throw new PayverseException(AuthErrorCode.AUTH_PIN_ALREADY_SET, "Use change or reset instead");
        }
        pins.save(TransactionPin.create(userId, pinEncoder.encode(pin), clock.instant()));
    }

    @Transactional(noRollbackFor = PayverseException.class)
    public void changePin(UUID userId, String currentPin, String newPin) {
        Pin next = Pin.choose(newPin);
        TransactionPin pin = requirePin(userId);
        check(pin, new Pin(currentPin));
        pin.replace(pinEncoder.encode(next), clock.instant());
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
        if (findUser(mobile).isPresent()) {
            otpService.request(mobile, OtpPurpose.PIN_RESET);
        }
    }

    @Transactional
    public void confirmReset(MobileNumber mobile, String otp, String newPin) {
        Pin next = Pin.choose(newPin);
        otpService.verify(mobile, OtpPurpose.PIN_RESET, otp);
        User user = findUser(mobile)
                .orElseThrow(() -> new PayverseException(AuthErrorCode.AUTH_OTP_EXPIRED, "Request a new OTP"));
        user.ensureActive();
        Instant now = clock.instant();
        String hash = pinEncoder.encode(next);
        pins.findById(user.getId()).ifPresentOrElse(
                existing -> existing.replace(hash, now),
                () -> pins.save(TransactionPin.create(user.getId(), hash, now)));
    }

    @Transactional(readOnly = true)
    public boolean isSet(UUID userId) {
        return pins.existsById(userId);
    }

    private Optional<User> findUser(MobileNumber mobile) {
        return users.findByMobileHash(blindIndexer.index(mobile.value()));
    }

    private TransactionPin requirePin(UUID userId) {
        return pins.findById(userId).orElseThrow(() -> new PayverseException(AuthErrorCode.AUTH_PIN_NOT_SET, "Set a PIN first"));
    }

    private void check(TransactionPin pin, Pin candidate) {
        Instant now = clock.instant();
        boolean matched = !pin.isLocked(now) && pinEncoder.matches(candidate, pin.getPinHash());
        PinVerification result = pin.recordAttempt(matched, now);
        pins.saveAndFlush(pin);
        switch (result) {
            case PinVerification.Valid valid -> { }
            case PinVerification.Incorrect incorrect -> throw new PayverseException(AuthErrorCode.AUTH_PIN_INCORRECT, null,
                    Map.of("remainingAttempts", incorrect.remainingAttempts()));
            case PinVerification.Locked locked -> {
                if (locked.justLocked()) {
                    events.pinLocked(pin.getUserId(), locked.until());
                }
                throw new PayverseException(AuthErrorCode.AUTH_PIN_LOCKED, null, Map.of("lockedUntil", locked.until().toString()));
            }
        }
    }
}
