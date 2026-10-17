package com.payverse.fraud.entity;

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

/** A REVIEW or BLOCK assessment waiting for (or resolved by) a human. */
@Entity
@Table(name = "flags")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Flag {

    @Id
    private UUID id;

    @Column(name = "assessment_id", nullable = false, updatable = false)
    private UUID assessmentId;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Decision decision;

    @Column(nullable = false, updatable = false)
    private int score;

    @Column(nullable = false, updatable = false)
    private String reasons;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FlagStatus status;

    @Column(name = "resolved_by")
    private String resolvedBy;

    @Column(name = "resolution_note")
    private String resolutionNote;

    @Column(name = "wallet_frozen", nullable = false)
    private boolean walletFrozen;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version
    private long version;

    public static Flag raise(RiskAssessment assessment, Instant now) {
        if (assessment.getDecision() == Decision.ALLOW) {
            throw new IllegalArgumentException("ALLOW assessments are not flagged");
        }
        Flag flag = new Flag();
        flag.id = UUID.randomUUID();
        flag.assessmentId = assessment.getId();
        flag.transactionId = assessment.getTransactionId();
        flag.userId = assessment.getUserId();
        flag.decision = assessment.getDecision();
        flag.score = assessment.getScore();
        flag.reasons = assessment.getReasons();
        flag.status = FlagStatus.OPEN;
        flag.createdAt = now;
        return flag;
    }

    public boolean isOpen() {
        return status == FlagStatus.OPEN;
    }

    public void resolve(FlagStatus resolution, String by, String note, boolean frozeWallet, Instant now) {
        if (!isOpen()) {
            throw new IllegalStateException("Flag " + id + " is already " + status);
        }
        if (resolution == FlagStatus.OPEN) {
            throw new IllegalArgumentException("Resolve as CONFIRMED_FRAUD or FALSE_POSITIVE");
        }
        if (frozeWallet && resolution != FlagStatus.CONFIRMED_FRAUD) {
            throw new IllegalArgumentException("Only confirmed fraud freezes the wallet");
        }
        status = resolution;
        resolvedBy = by;
        resolutionNote = note == null ? null : note.length() <= 500 ? note : note.substring(0, 500);
        walletFrozen = frozeWallet;
        resolvedAt = now;
    }
}
