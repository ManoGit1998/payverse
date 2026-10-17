package com.payverse.transaction.dto;

import com.payverse.transaction.entity.TransactionStatus;
import com.payverse.transaction.entity.TransferSource;

import java.time.Instant;
import java.util.UUID;

/** The payer's view of a transfer. {@code status} PENDING means the saga is still finishing (poll GET /transfers/{id}). */
public record TransferResponse(UUID transactionId, TransactionStatus status, MoneyDto amount, PayeeView payee, String note,
                               String failureCode, String failureReason, TransferSource source, Instant createdAt,
                               Instant completedAt) {
}
