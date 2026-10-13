package com.payverse.common.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Polling publisher for the transactional outbox. Each poll claims a batch with
 * {@code FOR UPDATE SKIP LOCKED} (safe with several instances), sends each event synchronously in
 * creation order and marks it published. On a send failure it stops the batch so per-aggregate
 * order is preserved; the row is retried on the next poll. Delivery is at-least-once, which is why
 * every consumer de-duplicates via {@link ProcessedEventGuard}.
 */
public class OutboxRelay implements SmartLifecycle {

    public static final String HEADER_EVENT_ID = "eventId";
    public static final String HEADER_EVENT_TYPE = "eventType";
    public static final String HEADER_CORRELATION_ID = "correlationId";

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;

    private volatile boolean running;
    private Thread worker;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate transactionTemplate,
                       KafkaTemplate<String, String> kafkaTemplate, OutboxProperties properties) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    record PendingEvent(UUID id, String topic, String eventType, String aggregateId, String payload, String correlationId) {
    }

    /** Publishes one batch; returns the number of events published. */
    public int relayBatch() {
        Integer published = transactionTemplate.execute(status -> {
            List<PendingEvent> batch = jdbc.query("""
                            SELECT id, topic, event_type, aggregate_id, payload::text AS payload, correlation_id
                            FROM outbox_events
                            WHERE published_at IS NULL
                            ORDER BY created_at
                            LIMIT ?
                            FOR UPDATE SKIP LOCKED
                            """,
                    (rs, i) -> new PendingEvent(rs.getObject("id", UUID.class), rs.getString("topic"),
                            rs.getString("event_type"), rs.getString("aggregate_id"), rs.getString("payload"),
                            rs.getString("correlation_id")),
                    properties.batchSize());
            int count = 0;
            for (PendingEvent event : batch) {
                try {
                    send(event);
                    jdbc.update("UPDATE outbox_events SET published_at = clock_timestamp(), attempts = attempts + 1 WHERE id = ?", event.id());
                    count++;
                } catch (Exception e) {
                    log.error("Outbox publish failed for event {} ({}); will retry", event.id(), event.eventType(), e);
                    jdbc.update("UPDATE outbox_events SET attempts = attempts + 1, last_error = ? WHERE id = ?",
                            abbreviate(e.toString()), event.id());
                    break;
                }
            }
            return count;
        });
        return published == null ? 0 : published;
    }

    /** Deletes published rows older than the retention period. */
    public int purgePublished() {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(properties.retention()));
        return jdbc.update("DELETE FROM outbox_events WHERE published_at IS NOT NULL AND published_at < ?", cutoff);
    }

    private void send(PendingEvent event) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(event.topic(), event.aggregateId(), event.payload());
        record.headers().add(new RecordHeader(HEADER_EVENT_ID, event.id().toString().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(HEADER_EVENT_TYPE, event.eventType().getBytes(StandardCharsets.UTF_8)));
        if (event.correlationId() != null) {
            record.headers().add(new RecordHeader(HEADER_CORRELATION_ID, event.correlationId().getBytes(StandardCharsets.UTF_8)));
        }
        kafkaTemplate.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void loop() {
        long iterations = 0;
        while (running) {
            try {
                int published = relayBatch();
                if (++iterations % 7_200 == 0) {
                    purgePublished();
                }
                if (published < properties.batchSize()) {
                    Thread.sleep(properties.pollInterval());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("Outbox relay iteration failed", e);
                sleepQuietly();
            }
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(properties.pollInterval().multipliedBy(4));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String abbreviate(String s) {
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }

    @Override
    public void start() {
        running = true;
        worker = Thread.ofVirtual().name("outbox-relay").start(this::loop);
        log.info("Outbox relay started (batch={}, poll={})", properties.batchSize(), properties.pollInterval());
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(properties.sendTimeout().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Start after the Kafka producer and stop before it. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
