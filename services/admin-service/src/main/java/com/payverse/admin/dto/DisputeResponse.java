package com.payverse.admin.dto;

import com.payverse.admin.entity.Dispute;
import com.payverse.admin.entity.DisputeOutcome;
import com.payverse.admin.entity.DisputeStatus;

import java.time.Instant;
import java.util.UUID;

public record DisputeResponse(UUID id, UUID transactionId, UUID userId, String reason, DisputeStatus status, DisputeOutcome outcome,
                              String assignee, String resolutionNote, UUID refundId, Instant createdAt, Instant resolvedAt) {

    public static DisputeResponse of(Dispute d) {
        return new DisputeResponse(d.getId(), d.getTransactionId(), d.getUserId(), d.getReason(), d.getStatus(), d.getOutcome(),
                d.getAssignee(), d.getResolutionNote(), d.getRefundId(), d.getCreatedAt(), d.getResolvedAt());
    }
}
