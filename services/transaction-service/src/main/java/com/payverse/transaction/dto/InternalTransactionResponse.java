package com.payverse.transaction.dto;

import com.payverse.transaction.entity.RefundStatus;
import com.payverse.transaction.entity.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Service view of a transaction (admin-service, reconciliation-service). */
public record InternalTransactionResponse(UUID id, String type, TransactionStatus status, UUID payerUserId, UUID payeeUserId, String payeeRef,
                                          BigDecimal amount, String currency, String failureCode, Instant createdAt, Instant completedAt,
                                          UUID refundId, RefundStatus refundStatus, String refundFailureCode) {
}
