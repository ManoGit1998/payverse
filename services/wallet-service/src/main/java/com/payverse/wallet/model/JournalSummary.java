package com.payverse.wallet.model;

import com.payverse.wallet.entity.JournalType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One journal with its amount (both entries carry the same amount), for reconciliation. */
public record JournalSummary(UUID journalId, JournalType type, String referenceType, String referenceId, BigDecimal amount,
                             Instant createdAt, UUID reversesJournalId) {
}
