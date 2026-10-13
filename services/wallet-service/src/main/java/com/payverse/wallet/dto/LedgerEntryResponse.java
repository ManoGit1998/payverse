package com.payverse.wallet.dto;

import com.payverse.wallet.entity.EntrySide;
import com.payverse.wallet.entity.JournalType;

import java.time.Instant;
import java.util.UUID;

public record LedgerEntryResponse(UUID entryId, UUID journalId, JournalType type, EntrySide side, MoneyDto amount,
                                  MoneyDto balanceAfter, String referenceType, String referenceId, String description,
                                  Instant createdAt) {
}
