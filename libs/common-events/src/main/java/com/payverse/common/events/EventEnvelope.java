package com.payverse.common.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Wrapper for every event published to Kafka. The record key is {@link #aggregateId()} so that
 * events for one aggregate keep their order within a partition.
 *
 * @param eventId       unique id, used by consumers for de-duplication (processed_events)
 * @param eventType     logical type, e.g. {@code transaction.completed}
 * @param version       payload schema version
 * @param occurredAt    when the fact happened (UTC)
 * @param producer      producing service name
 * @param aggregateType e.g. {@code Transaction}
 * @param aggregateId   aggregate identifier, also the Kafka key
 * @param correlationId request correlation id for tracing across services
 * @param payload       event payload record
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int version,
        Instant occurredAt,
        String producer,
        String aggregateType,
        String aggregateId,
        String correlationId,
        T payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(payload, "payload");
    }

    /** Creates a new envelope for {@code type} with a random event id and the current time. */
    public static <T> EventEnvelope<T> create(EventType type, String producer, String aggregateId,
                                              String correlationId, T payload) {
        if (!type.payloadType().isInstance(payload)) {
            throw new IllegalArgumentException("Payload " + payload.getClass().getName()
                    + " does not match event type " + type.typeName());
        }
        return new EventEnvelope<>(UUID.randomUUID(), type.typeName(), type.version(), Instant.now(), producer,
                type.aggregateType(), aggregateId, correlationId, payload);
    }

    public String topic() {
        return EventType.fromTypeName(eventType).topic();
    }
}
