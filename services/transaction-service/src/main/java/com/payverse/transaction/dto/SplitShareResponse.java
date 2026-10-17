package com.payverse.transaction.dto;

import com.payverse.transaction.entity.MoneyRequestStatus;

import java.util.UUID;

public record SplitShareResponse(UUID moneyRequestId, String participant, MoneyDto amount, MoneyRequestStatus status, UUID transactionId) {
}
