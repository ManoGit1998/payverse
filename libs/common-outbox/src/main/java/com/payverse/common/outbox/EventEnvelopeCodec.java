package com.payverse.common.outbox;

import com.payverse.common.events.EventEnvelope;
import com.payverse.common.events.EventType;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** JSON (de)serialisation of {@link EventEnvelope}s, resolving the payload class from the event catalog. */
public class EventEnvelopeCodec {

    private final JsonMapper jsonMapper;

    public EventEnvelopeCodec(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public String encode(EventEnvelope<?> envelope) {
        return jsonMapper.writeValueAsString(envelope);
    }

    public EventEnvelope<?> decode(String json) {
        JsonNode tree = jsonMapper.readTree(json);
        JsonNode eventType = tree.get("eventType");
        if (eventType == null || !eventType.isString()) {
            throw new IllegalArgumentException("Message is not an EventEnvelope (missing eventType)");
        }
        return decode(json, EventType.fromTypeName(eventType.asString()).payloadType());
    }

    @SuppressWarnings("unchecked")
    public <T> EventEnvelope<T> decode(String json, Class<T> payloadType) {
        JavaType type = jsonMapper.getTypeFactory().constructParametricType(EventEnvelope.class, payloadType);
        return (EventEnvelope<T>) jsonMapper.readValue(json, type);
    }
}
