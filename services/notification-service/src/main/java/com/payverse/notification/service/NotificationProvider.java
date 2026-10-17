package com.payverse.notification.service;

import com.payverse.notification.entity.Channel;

import java.util.UUID;

/**
 * A delivery channel (SMS gateway, e-mail service, push service). MOCK implementations log instead
 * of sending; real adapters would look up the user's contact details through an internal API.
 */
public interface NotificationProvider {

    record Delivery(UUID notificationId, UUID userId, String subject, String body) {
    }

    /** Thrown when the provider rejects or fails a delivery (it will be retried). */
    class DeliveryFailedException extends RuntimeException {
        public DeliveryFailedException(String message) {
            super(message);
        }
    }

    Channel channel();

    /** @return the provider's message reference */
    String send(Delivery delivery);
}
