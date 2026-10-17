package com.payverse.recon.dto;

import com.payverse.recon.entity.MismatchStatus;
import com.payverse.recon.entity.ReconJob;
import com.payverse.recon.entity.ReconMismatch;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MismatchResponse(UUID id, UUID runId, ReconJob job, String type, String reference, BigDecimal expectedAmount,
                               BigDecimal actualAmount, MismatchStatus status, String note, String resolvedBy, Instant resolvedAt,
                               Instant createdAt) {

    public static MismatchResponse of(ReconMismatch m) {
        return new MismatchResponse(m.getId(), m.getRunId(), m.getJob(), m.getType(), m.getReference(), m.getExpectedAmount(),
                m.getActualAmount(), m.getStatus(), m.getNote(), m.getResolvedBy(), m.getResolvedAt(), m.getCreatedAt());
    }
}
