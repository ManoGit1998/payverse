package com.payverse.transaction.dto;

import com.payverse.transaction.entity.TransactionStatus;

import java.time.Instant;

public record StatusChangeResponse(TransactionStatus from, TransactionStatus to, String reason, Instant at) {
}
