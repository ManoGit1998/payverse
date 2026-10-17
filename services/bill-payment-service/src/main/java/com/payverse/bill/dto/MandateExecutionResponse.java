package com.payverse.bill.dto;

import com.payverse.bill.entity.ExecutionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MandateExecutionResponse(UUID id, Instant scheduledFor, UUID billPaymentId, BigDecimal amount, ExecutionStatus status,
                                       String reason, Instant completedAt) {
}
