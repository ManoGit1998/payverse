package com.payverse.wallet.dto;

import java.time.Instant;
import java.util.UUID;

public record AddMoneyResponse(UUID journalId, MoneyDto amount, MoneyDto balance, String paymentReference, String status,
                               Instant createdAt) {
}
