package com.payverse.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A UPI switch settlement record (RRN), for reconciliation against transactions. */
public record UpiSwitchRecordResponse(String rrn, UUID transactionId, BigDecimal amount, String status, Instant createdAt) {
}
