package com.payverse.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A settled PG charge, for reconciliation against the wallet ledger. */
public record PgChargeRecord(UUID orderId, UUID userId, BigDecimal amount, String providerReference, String status, Instant createdAt) {
}
