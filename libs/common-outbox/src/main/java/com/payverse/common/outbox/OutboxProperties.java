package com.payverse.common.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param producer          name stamped on every envelope (defaults to {@code spring.application.name})
 * @param relayEnabled      run the polling relay in this instance
 * @param batchSize         rows claimed per poll
 * @param pollInterval      delay between polls when the outbox is empty
 * @param sendTimeout       max wait for a Kafka ack per event
 * @param retention         published rows older than this are deleted
 */
@ConfigurationProperties("payverse.outbox")
public record OutboxProperties(String producer, Boolean relayEnabled, Integer batchSize, Duration pollInterval,
                               Duration sendTimeout, Duration retention) {

    public OutboxProperties {
        relayEnabled = relayEnabled == null || relayEnabled;
        batchSize = batchSize == null ? 100 : batchSize;
        pollInterval = pollInterval == null ? Duration.ofMillis(500) : pollInterval;
        sendTimeout = sendTimeout == null ? Duration.ofSeconds(10) : sendTimeout;
        retention = retention == null ? Duration.ofDays(7) : retention;
    }
}
