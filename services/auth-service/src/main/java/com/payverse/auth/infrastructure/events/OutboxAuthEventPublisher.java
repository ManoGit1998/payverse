package com.payverse.auth.infrastructure.events;

import com.payverse.auth.application.port.AuthEventPublisher;
import com.payverse.auth.domain.Device;
import com.payverse.auth.domain.User;
import com.payverse.common.events.EventType;
import com.payverse.common.events.payload.AuthEvents;
import com.payverse.common.outbox.OutboxPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
class OutboxAuthEventPublisher implements AuthEventPublisher {

    private final OutboxPublisher outbox;

    OutboxAuthEventPublisher(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    @Override
    public void userRegistered(User user, String referralCode) {
        outbox.publish(EventType.USER_REGISTERED, user.id().toString(),
                new AuthEvents.UserRegistered(user.id(), user.mobile().masked(), referralCode));
    }

    @Override
    public void newDeviceLogin(User user, Device device, String ipAddress) {
        outbox.publish(EventType.NEW_DEVICE_LOGIN, user.id().toString(),
                new AuthEvents.NewDeviceLogin(user.id(), device.deviceId().value(), device.name(), ipAddress));
    }

    @Override
    public void pinLocked(UUID userId, Instant lockedUntil) {
        outbox.publish(EventType.PIN_LOCKED, userId.toString(), new AuthEvents.PinLocked(userId, lockedUntil));
    }
}
