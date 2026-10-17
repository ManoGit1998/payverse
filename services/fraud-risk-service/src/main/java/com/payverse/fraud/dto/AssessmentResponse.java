package com.payverse.fraud.dto;

import com.payverse.fraud.entity.Decision;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AssessmentResponse(UUID id, UUID transactionId, UUID userId, UUID payeeUserId, String type, BigDecimal amount,
                                 int score, Decision decision, List<String> reasons, String modelVersion, Instant createdAt) {
}
