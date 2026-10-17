package com.payverse.fraud.service;

import com.payverse.fraud.entity.UserRiskProfile;
import com.payverse.fraud.repository.KnownDeviceRepository;
import com.payverse.fraud.repository.UserRiskProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Keeps per-user risk signals up to date from events (called inside the consumer transaction). */
@Service
public class RiskSignalService {

    private final UserRiskProfileRepository profiles;
    private final KnownDeviceRepository devices;
    private final Clock clock;

    public RiskSignalService(UserRiskProfileRepository profiles, KnownDeviceRepository devices, Clock clock) {
        this.profiles = profiles;
        this.devices = devices;
        this.clock = clock;
    }

    @Transactional
    public void newDevice(UUID userId, String deviceId, Instant at) {
        devices.recordFirstSeen(userId, deviceId, at);
    }

    @Transactional
    public void pinLocked(UUID userId, Instant lockedAt) {
        profile(userId).recordPinLock(lockedAt == null ? clock.instant() : lockedAt, clock.instant());
    }

    /** The payer's completed transfers build the baseline for amount anomalies. */
    @Transactional
    public void transferCompleted(UUID payerUserId, BigDecimal amount) {
        profile(payerUserId).recordCompleted(amount, clock.instant());
    }

    private UserRiskProfile profile(UUID userId) {
        return profiles.lockById(userId).orElseGet(() -> profiles.saveAndFlush(UserRiskProfile.empty(userId, clock.instant())));
    }
}
