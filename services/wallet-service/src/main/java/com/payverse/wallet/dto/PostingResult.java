package com.payverse.wallet.dto;

import com.payverse.common.money.Money;

import java.time.Instant;
import java.util.UUID;

/** Outcome of one wallet posting. {@code replayed} = the idempotency key had already been applied. */
public record PostingResult(UUID journalId, UUID walletId, UUID userId, Money balanceAfter, boolean replayed, Instant postedAt) {
}
