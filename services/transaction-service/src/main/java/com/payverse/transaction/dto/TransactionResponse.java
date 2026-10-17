package com.payverse.transaction.dto;

import com.payverse.transaction.entity.PayeeType;
import com.payverse.transaction.entity.TransactionStatus;
import com.payverse.transaction.entity.TransferSource;

import java.time.Instant;
import java.util.UUID;

/** History item from the viewer's perspective: SENT (money out) or RECEIVED (money in). */
public record TransactionResponse(UUID id, Direction direction, String counterparty, PayeeType payeeType, MoneyDto amount,
                                  TransactionStatus status, String failureCode, String note, TransferSource source,
                                  Instant createdAt, Instant completedAt) {

    public enum Direction { SENT, RECEIVED }
}
