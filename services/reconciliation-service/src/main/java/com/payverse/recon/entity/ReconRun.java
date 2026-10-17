package com.payverse.recon.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/** One execution of a reconciliation job for one business day (IST). */
@Entity
@Table(name = "recon_runs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconRun {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_name", nullable = false, updatable = false)
    private ReconJob job;

    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;

    @Column(name = "window_from", nullable = false, updatable = false)
    private Instant windowFrom;

    @Column(name = "window_to", nullable = false, updatable = false)
    private Instant windowTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status;

    @Column(name = "triggered_by", nullable = false, updatable = false)
    private String triggeredBy;

    @Column(name = "left_count", nullable = false)
    private int leftCount;

    @Column(name = "right_count", nullable = false)
    private int rightCount;

    @Column(name = "matched_count", nullable = false)
    private int matchedCount;

    @Column(name = "mismatch_count", nullable = false)
    private int mismatchCount;

    private String error;

    @Column(name = "job_execution_id")
    private Long jobExecutionId;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Version
    private long version;

    /** The window is the IST calendar day: [00:00, 24:00) Asia/Kolkata. */
    public static ReconRun start(ReconJob job, LocalDate businessDate, String triggeredBy, Instant now) {
        ReconRun run = new ReconRun();
        run.id = UUID.randomUUID();
        run.job = job;
        run.businessDate = businessDate;
        run.windowFrom = businessDate.atStartOfDay(IST).toInstant();
        run.windowTo = businessDate.plusDays(1).atStartOfDay(IST).toInstant();
        run.status = RunStatus.RUNNING;
        run.triggeredBy = triggeredBy;
        run.startedAt = now;
        return run;
    }

    public void attachExecution(long executionId) {
        jobExecutionId = executionId;
    }

    public void complete(int left, int right, int matched, int mismatches, Instant now) {
        leftCount = left;
        rightCount = right;
        matchedCount = matched;
        mismatchCount = mismatches;
        status = RunStatus.COMPLETED;
        finishedAt = now;
    }

    public void fail(String reason, Instant now) {
        if (status == RunStatus.RUNNING) {
            status = RunStatus.FAILED;
            error = reason == null ? "unknown error" : reason.length() <= 500 ? reason : reason.substring(0, 500);
            finishedAt = now;
        }
    }
}
