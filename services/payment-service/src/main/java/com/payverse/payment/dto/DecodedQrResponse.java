package com.payverse.payment.dto;

import com.payverse.payment.entity.QrType;

import java.time.Instant;
import java.util.UUID;

public record DecodedQrResponse(UUID qrId, String vpa, String payeeName, MoneyDto amount, String note, QrType type, Instant expiresAt) {
}
