package com.payverse.recon.dto;

import com.payverse.recon.entity.ReconJob;
import com.payverse.recon.entity.ReconRun;
import com.payverse.recon.entity.RunStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ReconRunResponse(UUID id, ReconJob job, LocalDate businessDate, Instant windowFrom, Instant windowTo, RunStatus status,
                               String triggeredBy, int leftCount, int rightCount, int matchedCount, int mismatchCount, String error,
                               Long jobExecutionId, Instant startedAt, Instant finishedAt) {

    public static ReconRunResponse of(ReconRun r) {
        return new ReconRunResponse(r.getId(), r.getJob(), r.getBusinessDate(), r.getWindowFrom(), r.getWindowTo(), r.getStatus(),
                r.getTriggeredBy(), r.getLeftCount(), r.getRightCount(), r.getMatchedCount(), r.getMismatchCount(), r.getError(),
                r.getJobExecutionId(), r.getStartedAt(), r.getFinishedAt());
    }
}
