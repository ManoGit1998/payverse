package com.payverse.payment.dto;

import com.payverse.payment.entity.CollectStatus;

import java.time.Instant;
import java.util.UUID;

public record CollectResponse(UUID id, String payerVpa, String payeeVpa, MoneyDto amount, String note, CollectStatus status,
                              Instant expiresAt, UUID transactionId, Instant createdAt) {
}
