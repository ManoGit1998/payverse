package com.payverse.admin.dto;

import com.payverse.admin.entity.RefundRequest;
import com.payverse.admin.entity.RefundStatus;

import java.time.Instant;
import java.util.UUID;

public record RefundResponse(UUID id, UUID transactionId, String amount, String currency, String reason, UUID disputeId, String requestedBy,
                             String decidedBy, RefundStatus status, String failureCode, int attempts, Instant createdAt, Instant decidedAt,
                             Instant completedAt) {

    public static RefundResponse of(RefundRequest r) {
        return new RefundResponse(r.getId(), r.getTransactionId(), r.getAmount().toPlainString(), r.getCurrency(), r.getReason(), r.getDisputeId(),
                r.getRequestedBy(), r.getDecidedBy(), r.getStatus(), r.getFailureCode(), r.getAttempts(), r.getCreatedAt(), r.getDecidedAt(),
                r.getCompletedAt());
    }
}
