package com.payverse.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** A Kafka message that exhausted its retries in some consumer and landed on a {@code .dlt} topic. */
@Entity
@Table(name = "dead_letter_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeadLetterEvent {

    @Id
    private UUID id;

    @Column(name = "dlt_topic", nullable = false)
    private String dltTopic;

    @Column(name = "original_topic", nullable = false)
    private String originalTopic;

    @Column(name = "partition_no", nullable = false)
    private int partitionNo;

    @Column(name = "offset_no", nullable = false)
    private long offsetNo;

    @Column(name = "message_key")
    private String messageKey;

    @Column(nullable = false)
    private String payload;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "replayed_at")
    private Instant replayedAt;

    public static DeadLetterEvent capture(String dltTopic, int partition, long offset, String key, String payload, String error, Instant now) {
        DeadLetterEvent event = new DeadLetterEvent();
        event.id = UUID.randomUUID();
        event.dltTopic = dltTopic;
        event.originalTopic = dltTopic.endsWith(".dlt") ? dltTopic.substring(0, dltTopic.length() - 4) : dltTopic;
        event.partitionNo = partition;
        event.offsetNo = offset;
        event.messageKey = key;
        event.payload = payload;
        event.errorMessage = error == null || error.length() <= 1000 ? error : error.substring(0, 1000);
        event.receivedAt = now;
        return event;
    }

    public boolean isReplayed() {
        return replayedAt != null;
    }

    public void markReplayed(Instant now) {
        replayedAt = now;
    }
}
