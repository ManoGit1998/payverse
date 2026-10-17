package com.payverse.notification.messaging;

import com.payverse.notification.service.DeadLetterService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Captures every {@code payverse.*.dlt} message from any service into {@code dead_letter_events}.
 * Uses its own container factory that retries forever instead of dead-lettering the dead letter.
 */
@Component
public class DeadLetterListener {

    private final DeadLetterService deadLetters;

    public DeadLetterListener(DeadLetterService deadLetters) {
        this.deadLetters = deadLetters;
    }

    @KafkaListener(topicPattern = "payverse\\..*\\.dlt", groupId = "notification-service.dlt",
            containerFactory = "deadLetterContainerFactory")
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        String group = header(record, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP);
        String error = header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        String described = group == null ? error : "[" + group + "] " + (error == null ? "" : error);
        deadLetters.capture(record.topic(), record.partition(), record.offset(), record.key(),
                record.value() == null ? "" : record.value(), described);
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
