package com.payverse.auth.service;

import com.payverse.auth.entity.Device;
import com.payverse.auth.entity.User;
import com.payverse.common.events.EventType;
import com.payverse.common.events.payload.AuthEvents;
import com.payverse.common.outbox.OutboxPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/** Publishes auth events through the transactional outbox. Must be called inside the business transaction. */
@Service
public class AuthEventPublisher {

    private final OutboxPublisher outbox;

    public AuthEventPublisher(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    public void userRegistered(User user, String referralCode) {
        outbox.publish(EventType.USER_REGISTERED, user.getId().toString(),
                new AuthEvents.UserRegistered(user.getId(), user.getMobileNumber().masked(), referralCode));
    }

    public void newDeviceLogin(User user, Device device, String ipAddress) {
        outbox.publish(EventType.NEW_DEVICE_LOGIN, user.getId().toString(),
                new AuthEvents.NewDeviceLogin(user.getId(), device.getDeviceId(), device.getName(), ipAddress));
    }

    public void pinLocked(UUID userId, Instant lockedUntil) {
        outbox.publish(EventType.PIN_LOCKED, userId.toString(), new AuthEvents.PinLocked(userId, lockedUntil));
    }
}
