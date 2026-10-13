package com.payverse.auth.application.port;

import com.payverse.auth.domain.Device;
import com.payverse.auth.domain.User;

import java.time.Instant;
import java.util.UUID;

/** Publishes auth domain events (transactional outbox). Must be called inside the business transaction. */
public interface AuthEventPublisher {

    void userRegistered(User user, String referralCode);

    void newDeviceLogin(User user, Device device, String ipAddress);

    void pinLocked(UUID userId, Instant lockedUntil);
}
