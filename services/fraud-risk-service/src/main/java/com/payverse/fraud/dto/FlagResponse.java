package com.payverse.fraud.dto;

import com.payverse.fraud.entity.Decision;
import com.payverse.fraud.entity.FlagStatus;

import java.time.Instant;
import java.util.UUID;

public record FlagResponse(UUID id, UUID assessmentId, UUID transactionId, UUID userId, Decision decision, int score, String reasons,
                           FlagStatus status, String resolvedBy, String resolutionNote, boolean walletFrozen, Instant createdAt,
                           Instant resolvedAt) {
}
