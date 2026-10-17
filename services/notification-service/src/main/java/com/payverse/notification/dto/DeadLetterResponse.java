package com.payverse.notification.dto;

import java.time.Instant;
import java.util.UUID;

public record DeadLetterResponse(UUID id, String dltTopic, String originalTopic, int partition, long offset, String messageKey,
                                 String payload, String errorMessage, Instant receivedAt, Instant replayedAt) {
}
