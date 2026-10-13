package com.payverse.wallet.dto;

import java.time.Instant;
import java.util.UUID;

public record WithdrawResponse(UUID journalId, MoneyDto amount, MoneyDto balance, UUID bankAccountId, String maskedAccountNumber,
                               String payoutReference, String status, Instant createdAt) {
}
