package com.payverse.admin.entity;

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
import java.util.UUID;

/** A user's complaint about a transaction: OPEN → IN_REVIEW (assigned) → RESOLVED (REFUND or REJECTED). */
@Entity
@Table(name = "disputes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Dispute {

    @Id
    private UUID id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DisputeStatus status;

    @Enumerated(EnumType.STRING)
    private DisputeOutcome outcome;

    private String assignee;

    @Column(name = "resolution_note")
    private String resolutionNote;

    @Column(name = "refund_id")
    private UUID refundId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version
    private long version;

    public static Dispute open(UUID transactionId, UUID userId, String reason, Instant now) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Tell us what went wrong");
        }
        Dispute d = new Dispute();
        d.id = UUID.randomUUID();
        d.transactionId = transactionId;
        d.userId = userId;
        d.reason = reason.length() <= 500 ? reason.trim() : reason.substring(0, 500);
        d.status = DisputeStatus.OPEN;
        d.createdAt = now;
        d.updatedAt = now;
        return d;
    }

    public void assign(String agent, Instant now) {
        if (status == DisputeStatus.RESOLVED) {
            throw new IllegalStateException("Dispute " + id + " is resolved");
        }
        assignee = agent;
        status = DisputeStatus.IN_REVIEW;
        updatedAt = now;
    }

    public void resolve(DisputeOutcome result, String by, String note, UUID refund, Instant now) {
        if (status == DisputeStatus.RESOLVED) {
            throw new IllegalStateException("Dispute " + id + " is already resolved");
        }
        if ((result == DisputeOutcome.REFUND) != (refund != null)) {
            throw new IllegalArgumentException("A REFUND outcome needs a refund request, REJECTED none");
        }
        status = DisputeStatus.RESOLVED;
        outcome = result;
        assignee = assignee == null ? by : assignee;
        resolutionNote = note == null || note.length() <= 500 ? note : note.substring(0, 500);
        refundId = refund;
        resolvedAt = now;
        updatedAt = now;
    }
}
