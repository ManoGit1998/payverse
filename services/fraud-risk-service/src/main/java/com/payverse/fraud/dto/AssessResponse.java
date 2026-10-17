package com.payverse.fraud.dto;

import com.payverse.fraud.entity.Decision;

import java.util.List;
import java.util.UUID;

public record AssessResponse(UUID assessmentId, UUID transactionId, int score, Decision decision, List<String> reasons, String modelVersion) {
}
