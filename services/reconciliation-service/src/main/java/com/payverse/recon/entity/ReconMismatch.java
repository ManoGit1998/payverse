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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A difference found by a run; stays OPEN until someone explains or fixes it. */
@Entity
@Table(name = "recon_mismatches")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconMismatch {

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_name", nullable = false, updatable = false)
    private ReconJob job;

    @Column(nullable = false, updatable = false)
    private String type;

    @Column(nullable = false, updatable = false)
    private String reference;

    @Column(name = "expected_amount", updatable = false)
    private BigDecimal expectedAmount;

    @Column(name = "actual_amount", updatable = false)
    private BigDecimal actualAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MismatchStatus status;

    private String note;

    @Column(name = "resolved_by")
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    public static ReconMismatch open(UUID runId, ReconJob job, String type, String reference, BigDecimal expected, BigDecimal actual,
                                     Instant now) {
        ReconMismatch m = new ReconMismatch();
        m.id = UUID.randomUUID();
        m.runId = runId;
        m.job = job;
        m.type = type;
        m.reference = reference;
        m.expectedAmount = expected;
        m.actualAmount = actual;
        m.status = MismatchStatus.OPEN;
        m.createdAt = now;
        return m;
    }

    public void resolve(String by, String why, Instant now) {
        if (status != MismatchStatus.OPEN) {
            throw new IllegalStateException("Mismatch " + id + " is already resolved");
        }
        status = MismatchStatus.RESOLVED;
        resolvedBy = by;
        note = why == null || why.length() <= 500 ? why : why.substring(0, 500);
        resolvedAt = now;
    }
}
