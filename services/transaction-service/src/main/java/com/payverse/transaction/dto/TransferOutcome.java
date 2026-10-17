package com.payverse.transaction.dto;

import com.payverse.transaction.entity.TransactionStatus;

import java.util.UUID;

/** Internal API result (for payment-service collect approvals). */
public record TransferOutcome(UUID transactionId, TransactionStatus status, String failureCode, String failureReason) {
}
