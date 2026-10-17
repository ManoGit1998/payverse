package com.payverse.notification.config;

import com.payverse.notification.entity.Channel;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * @param maxAttempts         delivery attempts before a notification is DEAD (default 5)
 * @param dispatchBatch       notifications sent per dispatcher run (default 50)
 * @param mockFailingChannels MOCK providers for these channels always fail (to exercise retries/DEAD)
 */
@ConfigurationProperties("payverse.notification")
public record NotificationProperties(Integer maxAttempts, Integer dispatchBatch, Set<Channel> mockFailingChannels) {

    public NotificationProperties {
        maxAttempts = maxAttempts == null ? 5 : maxAttempts;
        dispatchBatch = dispatchBatch == null ? 50 : dispatchBatch;
        mockFailingChannels = mockFailingChannels == null ? Set.of() : Set.copyOf(mockFailingChannels);
    }
}
