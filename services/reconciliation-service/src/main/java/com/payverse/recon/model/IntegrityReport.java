package com.payverse.recon.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** wallet-service's ledger integrity report. */
public record IntegrityReport(boolean healthy, List<UUID> walletsOutOfBalance, List<UUID> unbalancedJournals, BigDecimal transferSuspense) {
}
