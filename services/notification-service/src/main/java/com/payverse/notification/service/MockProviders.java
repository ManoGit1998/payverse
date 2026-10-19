package com.payverse.notification.service;

import com.payverse.notification.config.NotificationProperties;
import com.payverse.notification.entity.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * MOCK SMS / e-mail / push providers: they log the message (bodies contain only masked data) and
 * return a fake reference. Channels listed in {@code payverse.notification.mock-failing-channels}
 * always fail, to exercise retries and DEAD notifications.
 */
@Configuration(proxyBeanMethods = false)
public class MockProviders {

    @Bean
    @ConditionalOnProperty(name = "payverse.providers.notification-sms.adapter", havingValue = "mock", matchIfMissing = true)
    NotificationProvider mockSmsProvider(NotificationProperties properties) {
        return new MockProvider(Channel.SMS, "MockSmsProvider", properties);
    }

    @Bean
    @ConditionalOnProperty(name = "payverse.providers.notification-email.adapter", havingValue = "mock", matchIfMissing = true)
    NotificationProvider mockEmailProvider(NotificationProperties properties) {
        return new MockProvider(Channel.EMAIL, "MockEmailProvider", properties);
    }

    @Bean
    @ConditionalOnProperty(name = "payverse.providers.notification-push.adapter", havingValue = "mock", matchIfMissing = true)
    NotificationProvider mockPushProvider(NotificationProperties properties) {
        return new MockProvider(Channel.PUSH, "MockPushProvider", properties);
    }

    static final class MockProvider implements NotificationProvider {

        private static final Logger log = LoggerFactory.getLogger(MockProvider.class);

        private final Channel channel;
        private final String name;
        private final NotificationProperties properties;

        MockProvider(Channel channel, String name, NotificationProperties properties) {
            this.channel = channel;
            this.name = name;
            this.properties = properties;
            log.warn("{} (MOCK) is active: {} messages are logged, not delivered.", name, channel);
        }

        @Override
        public Channel channel() {
            return channel;
        }

        @Override
        public String send(Delivery delivery) {
            if (properties.mockFailingChannels().contains(channel)) {
                throw new DeliveryFailedException(name + " is configured to fail (mock)");
            }
            String reference = channel.name().toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 13);
            log.atInfo().addKeyValue("mock", channel.name().toLowerCase()).addKeyValue("userId", delivery.userId())
                    .log("[MOCK {}] to user {}: {}{}", channel, delivery.userId(),
                            delivery.subject() == null ? "" : delivery.subject() + " — ", delivery.body());
            return reference;
        }
    }
}
