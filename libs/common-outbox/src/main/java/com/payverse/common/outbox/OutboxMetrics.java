package com.payverse.common.outbox;

import com.payverse.common.events.Amount;
import com.payverse.common.events.EventEnvelope;
import com.payverse.common.events.EventType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Business metrics for free: every domain event committed through the outbox is counted by type
 * ({@code payverse.events}), and events whose payload has an {@code amount} also feed
 * {@code payverse.events.amount} (INR). Plus the outbox backlog and the age of its oldest row.
 */
public class OutboxMetrics {

    private final MeterRegistry registry;
    private final Map<Class<?>, Optional<RecordComponent>> amountComponents = new ConcurrentHashMap<>();

    public OutboxMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        this.registry = registry;
        // Start every series at 0: with OTLP push the first exported value would otherwise already include the first
        // events, and Prometheus' increase()/rate() cannot see an increment before a series' first sample.
        for (EventType type : EventType.values()) {
            counter(type.typeName());
            summary(type.typeName());
        }
        Gauge.builder("payverse.outbox.pending", jdbc, j -> count(j, "SELECT count(*) FROM outbox_events WHERE published_at IS NULL"))
                .description("Outbox events not yet published to Kafka").register(registry);
        Gauge.builder("payverse.outbox.oldest.age", jdbc, j -> count(j,
                        "SELECT COALESCE(EXTRACT(EPOCH FROM clock_timestamp() - min(created_at)), 0) FROM outbox_events WHERE published_at IS NULL"))
                .description("Age of the oldest unpublished outbox event").baseUnit("seconds").register(registry);
    }

    /** Called after the business transaction committed. */
    public void committed(EventEnvelope<?> envelope) {
        counter(envelope.eventType()).increment();
        amount(envelope.payload()).ifPresent(value -> summary(envelope.eventType()).record(value.doubleValue()));
    }

    private Counter counter(String eventType) {
        return Counter.builder("payverse.events").description("Domain events committed (outbox)").tag("event_type", eventType).register(registry);
    }

    private DistributionSummary summary(String eventType) {
        return DistributionSummary.builder("payverse.events.amount").description("Amounts carried by domain events").baseUnit("inr")
                .tag("event_type", eventType).register(registry);
    }

    Optional<BigDecimal> amount(Object payload) {
        if (payload == null || !payload.getClass().isRecord()) {
            return Optional.empty();
        }
        Optional<RecordComponent> component = amountComponents.computeIfAbsent(payload.getClass(), type -> {
            for (RecordComponent c : type.getRecordComponents()) {
                if (c.getName().equals("amount") && c.getType() == Amount.class) {
                    return Optional.of(c);
                }
            }
            return Optional.empty();
        });
        return component.map(c -> {
            try {
                Amount a = (Amount) c.getAccessor().invoke(payload);
                return a == null ? null : a.value();
            } catch (ReflectiveOperationException e) {
                return null;
            }
        });
    }

    private static double count(JdbcTemplate jdbc, String sql) {
        try {
            Number n = jdbc.queryForObject(sql, Number.class);
            return n == null ? 0 : n.doubleValue();
        } catch (RuntimeException e) {
            return Double.NaN; // database unavailable: report "no value" rather than failing the export
        }
    }
}
