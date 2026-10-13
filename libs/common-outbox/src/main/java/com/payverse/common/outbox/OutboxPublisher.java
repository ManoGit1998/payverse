package com.payverse.common.outbox;

import com.payverse.common.events.EventEnvelope;
import com.payverse.common.events.EventType;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Writes events to the {@code outbox_events} table <b>inside the caller's transaction</b>, so the
 * business change and the event commit (or roll back) together. {@link OutboxRelay} publishes them.
 */
public class OutboxPublisher {

    private final JdbcTemplate jdbc;
    private final EventEnvelopeCodec codec;
    private final String producer;

    public OutboxPublisher(JdbcTemplate jdbc, EventEnvelopeCodec codec, String producer) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.producer = producer;
    }

    /** Builds an envelope (producer + current correlation id) and stores it. */
    public <T> EventEnvelope<T> publish(EventType type, String aggregateId, T payload) {
        EventEnvelope<T> envelope = EventEnvelope.create(type, producer, aggregateId, MDC.get("correlationId"), payload);
        publish(envelope);
        return envelope;
    }

    public void publish(EventEnvelope<?> envelope) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("OutboxPublisher.publish must be called inside a transaction");
        }
        jdbc.update("""
                        INSERT INTO outbox_events (id, topic, event_type, aggregate_type, aggregate_id, payload, correlation_id)
                        VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                        """,
                envelope.eventId(), envelope.topic(), envelope.eventType(), envelope.aggregateType(),
                envelope.aggregateId(), codec.encode(envelope), envelope.correlationId());
    }
}
