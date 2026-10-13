package com.payverse.common.outbox;

import com.payverse.common.events.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Makes Kafka consumers idempotent: the handler runs only if {@code (eventId, consumer)} was not
 * processed before, and the marker row commits in the same transaction as the handler's changes.
 */
public class ProcessedEventGuard {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventGuard.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;

    public ProcessedEventGuard(JdbcTemplate jdbc, TransactionTemplate transactionTemplate) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
    }

    /** @return {@code true} if the handler ran, {@code false} if the event was a duplicate */
    public <T> boolean handleOnce(EventEnvelope<T> envelope, String consumer, Consumer<EventEnvelope<T>> handler) {
        return runOnce(envelope.eventId(), consumer, () -> handler.accept(envelope));
    }

    public boolean runOnce(UUID eventId, String consumer, Runnable handler) {
        Boolean ran = transactionTemplate.execute(status -> {
            int inserted = jdbc.update(
                    "INSERT INTO processed_events (event_id, consumer) VALUES (?, ?) ON CONFLICT DO NOTHING", eventId, consumer);
            if (inserted == 0) {
                log.info("Skipping duplicate event {} for consumer {}", eventId, consumer);
                return false;
            }
            handler.run();
            return true;
        });
        return Boolean.TRUE.equals(ran);
    }
}
