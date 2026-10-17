package com.payverse.transaction.dto;

import com.payverse.transaction.entity.MoneyRequestStatus;

import java.time.Instant;
import java.util.UUID;

/** {@code direction}: INCOMING = someone asks me to pay, OUTGOING = I asked. */
public record MoneyRequestResponse(UUID id, Direction direction, String counterparty, MoneyDto amount, String note,
                                   MoneyRequestStatus status, Instant expiresAt, UUID transactionId, UUID splitBillId,
                                   Instant createdAt) {

    public enum Direction { INCOMING, OUTGOING }
}
