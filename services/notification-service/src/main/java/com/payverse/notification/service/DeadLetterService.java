package com.payverse.notification.service;

import com.payverse.common.error.CommonErrorCode;
import com.payverse.common.error.PayverseException;
import com.payverse.common.web.audit.AuditLogger;
import com.payverse.notification.entity.DeadLetterEvent;
import com.payverse.notification.exception.NotificationErrorCode;
import com.payverse.notification.repository.DeadLetterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Platform-wide dead-letter desk: every {@code <topic>.dlt} message is captured here so operators
 * can inspect it and, once the cause is fixed, replay it to the original topic (consumers are
 * idempotent on eventId, so a replay never double-applies).
 */
@Service
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    private final DeadLetterRepository deadLetters;
    private final KafkaTemplate<String, String> kafka;
    private final AuditLogger audit;
    private final Clock clock;

    public DeadLetterService(DeadLetterRepository deadLetters, KafkaTemplate<String, String> kafka, AuditLogger audit,
                             Clock clock) {
        this.audit = audit;
        this.deadLetters = deadLetters;
        this.kafka = kafka;
        this.clock = clock;
    }

    @Transactional
    public void capture(String dltTopic, int partition, long offset, String key, String payload, String error) {
        if (deadLetters.existsByDltTopicAndPartitionNoAndOffsetNo(dltTopic, partition, offset)) {
            return;
        }
        deadLetters.save(DeadLetterEvent.capture(dltTopic, partition, offset, key, payload, error, clock.instant()));
        log.error("Dead letter captured from {} (partition {}, offset {}): {}", dltTopic, partition, offset, error);
    }

    @Transactional(readOnly = true)
    public Page<DeadLetterEvent> list(boolean onlyOpen, int page, int size) {
        return onlyOpen ? deadLetters.findByReplayedAtIsNullOrderByReceivedAtDesc(PageRequest.of(page, size))
                : deadLetters.findAllByOrderByReceivedAtDesc(PageRequest.of(page, size));
    }

    @Transactional
    public DeadLetterEvent replay(String actor, UUID id) {
        DeadLetterEvent event = deadLetters.findById(id)
                .orElseThrow(() -> new PayverseException(NotificationErrorCode.NOTIFICATION_DEAD_LETTER_NOT_FOUND, null));
        if (event.isReplayed()) {
            throw new PayverseException(NotificationErrorCode.NOTIFICATION_ALREADY_REPLAYED, null);
        }
        try {
            kafka.send(event.getOriginalTopic(), event.getMessageKey(), event.getPayload()).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new PayverseException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE, "Kafka unavailable", Map.of(), e);
        }
        event.markReplayed(clock.instant());
        audit.record(actor, "DEAD_LETTER_REPLAYED", "DeadLetterEvent", id.toString(), Map.of("topic", event.getOriginalTopic()));
        log.info("Replayed dead letter {} to {}", id, event.getOriginalTopic());
        return event;
    }
}
