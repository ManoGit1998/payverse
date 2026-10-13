package com.payverse.wallet.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** One balanced posting (its entries' debits equal credits — enforced by a DB trigger). Immutable. */
@Entity
@Table(name = "journals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Journal {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JournalType type;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "reference_type")
    private String referenceType;

    @Column(name = "reference_id")
    private String referenceId;

    private String description;

    @Column(name = "reverses_journal_id")
    private UUID reversesJournalId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static Journal create(JournalType type, String idempotencyKey, String referenceType, String referenceId,
                                 String description, UUID reversesJournalId, Instant now) {
        Journal journal = new Journal();
        journal.id = UUID.randomUUID();
        journal.type = type;
        journal.idempotencyKey = idempotencyKey;
        journal.referenceType = referenceType;
        journal.referenceId = referenceId;
        journal.description = description == null || description.length() <= 200 ? description : description.substring(0, 200);
        journal.reversesJournalId = reversesJournalId;
        journal.createdAt = now;
        return journal;
    }
}
