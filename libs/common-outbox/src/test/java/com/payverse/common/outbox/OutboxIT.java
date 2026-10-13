package com.payverse.common.outbox;

import com.payverse.common.events.EventEnvelope;
import com.payverse.common.events.EventType;
import com.payverse.common.events.Topics;
import com.payverse.common.events.payload.WalletEvents;
import com.payverse.common.test.KafkaTestcontainer;
import com.payverse.common.test.PostgresTestcontainer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = OutboxTestApplication.class, properties = {
        "spring.application.name=common-outbox",
        "spring.main.web-application-type=none",
        "spring.flyway.locations=classpath:db/common",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "payverse.outbox.poll-interval=100ms"
})
@Import({PostgresTestcontainer.class, KafkaTestcontainer.class})
class OutboxIT {

    @Autowired
    OutboxPublisher publisher;

    @Autowired
    OutboxRelay relay;

    @Autowired
    ProcessedEventGuard guard;

    @Autowired
    EventEnvelopeCodec codec;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OutboxTestApplication.StatusListener listener;

    private static WalletEvents.WalletCreated walletCreated(UUID walletId) {
        return new WalletEvents.WalletCreated(walletId, UUID.randomUUID(), "MIN_KYC");
    }

    @Test
    void committedEventsArePublishedToKafkaWithHeaders() {
        UUID walletId = UUID.randomUUID();
        MDC.put("correlationId", "corr-outbox-1");
        EventEnvelope<?> envelope;
        try {
            envelope = tx.execute(s -> publisher.publish(EventType.WALLET_CREATED, walletId.toString(), walletCreated(walletId)));
        } finally {
            MDC.clear();
        }

        ConsumerRecord<String, String> record = pollFor(Topics.WALLET_CREATED, walletId.toString());
        assertThat(record.key()).isEqualTo(walletId.toString());
        assertThat(new String(record.headers().lastHeader(OutboxRelay.HEADER_EVENT_ID).value(), StandardCharsets.UTF_8))
                .isEqualTo(envelope.eventId().toString());
        assertThat(new String(record.headers().lastHeader(OutboxRelay.HEADER_CORRELATION_ID).value(), StandardCharsets.UTF_8))
                .isEqualTo("corr-outbox-1");

        EventEnvelope<?> decoded = codec.decode(record.value());
        assertThat(decoded).isEqualTo(envelope);
        assertThat(decoded.producer()).isEqualTo("common-outbox");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_events WHERE id = ?",
                        Boolean.class, envelope.eventId())).isTrue());
    }

    @Test
    void rolledBackTransactionsPublishNothing() {
        UUID walletId = UUID.randomUUID();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            publisher.publish(EventType.WALLET_CREATED, walletId.toString(), walletCreated(walletId));
            throw new IllegalStateException("business failure");
        })).isInstanceOf(IllegalStateException.class);

        Integer rows = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", Integer.class, walletId.toString());
        assertThat(rows).isZero();
    }

    @Test
    void publishingOutsideATransactionIsRejected() {
        UUID walletId = UUID.randomUUID();
        assertThatThrownBy(() -> publisher.publish(EventType.WALLET_CREATED, walletId.toString(), walletCreated(walletId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inside a transaction");
    }

    @Test
    void processedEventGuardRunsHandlerOnce() {
        UUID eventId = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();
        assertThat(guard.runOnce(eventId, "test-consumer", runs::incrementAndGet)).isTrue();
        assertThat(guard.runOnce(eventId, "test-consumer", runs::incrementAndGet)).isFalse();
        assertThat(guard.runOnce(eventId, "another-consumer", runs::incrementAndGet)).isTrue();
        assertThat(runs).hasValue(2);
    }

    @Test
    void handlerFailureRollsBackTheProcessedMarker() {
        UUID eventId = UUID.randomUUID();
        assertThatThrownBy(() -> guard.runOnce(eventId, "flaky", () -> { throw new IllegalStateException("boom"); }))
                .isInstanceOf(IllegalStateException.class);
        AtomicInteger runs = new AtomicInteger();
        assertThat(guard.runOnce(eventId, "flaky", runs::incrementAndGet)).isTrue();
        assertThat(runs).hasValue(1);
    }

    @Test
    void duplicateDeliveriesAreHandledOnce() {
        UUID walletId = UUID.randomUUID();
        var payload = new WalletEvents.WalletStatusChanged(walletId, UUID.randomUUID(), "FROZEN", "test", "admin");
        EventEnvelope<?> envelope = EventEnvelope.create(EventType.WALLET_STATUS_CHANGED, "test", walletId.toString(), null, payload);
        tx.executeWithoutResult(s -> publisher.publish(envelope));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_events WHERE id = ?",
                        Boolean.class, envelope.eventId())).isTrue());
        // simulate an at-least-once redelivery of the same event
        jdbc.update("UPDATE outbox_events SET published_at = NULL WHERE id = ?", envelope.eventId());
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE id = ?", Integer.class, envelope.eventId()))
                        .isGreaterThanOrEqualTo(2));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(listener.handled).contains(walletId.toString()));
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(listener.handled.stream().filter(walletId.toString()::equals).count()).isEqualTo(1));
    }

    @Test
    void poisonMessagesEndUpInTheDeadLetterTopic() {
        UUID walletId = UUID.randomUUID();
        var payload = new WalletEvents.WalletStatusChanged(walletId, UUID.randomUUID(), "POISON", "test", "admin");
        tx.executeWithoutResult(s -> publisher.publish(EventType.WALLET_STATUS_CHANGED, walletId.toString(), payload));

        ConsumerRecord<String, String> dead = pollFor(Topics.dlt(Topics.WALLET_STATUS_CHANGED), walletId.toString());
        assertThat(dead.value()).contains("POISON");
        assertThat(dead.headers().lastHeader("kafka_dlt-exception-message")).isNotNull();
    }

    @Test
    void purgeRemovesOldPublishedRows() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_events (id, topic, event_type, aggregate_type, aggregate_id, payload, published_at)
                VALUES (?, 't', 'x', 'X', 'a', '{}'::jsonb, now() - interval '30 days')
                """, id);
        assertThat(relay.purgePublished()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE id = ?", Integer.class, id)).isZero();
    }

    private static ConsumerRecord<String, String> pollFor(String topic, String key) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestcontainer.KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> found = new ArrayList<>();
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (key.equals(r.key())) {
                        found.add(r);
                    }
                });
                return !found.isEmpty();
            });
            return found.getFirst();
        }
    }
}
