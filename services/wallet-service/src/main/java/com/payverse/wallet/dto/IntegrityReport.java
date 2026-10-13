package com.payverse.wallet.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Ledger health: every wallet balance must equal the sum of its entries and every journal must
 * balance. {@code transferSuspense} is money in flight between saga steps (should drain to 0).
 */
public record IntegrityReport(boolean healthy, List<UUID> walletsOutOfBalance, List<UUID> unbalancedJournals,
                              MoneyDto transferSuspense, Instant checkedAt) {
}
