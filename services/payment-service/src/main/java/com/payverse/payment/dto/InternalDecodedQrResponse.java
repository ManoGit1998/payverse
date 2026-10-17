package com.payverse.payment.dto;

import com.payverse.payment.entity.QrType;

import java.time.Instant;
import java.util.UUID;

public record InternalDecodedQrResponse(UUID qrId, String vpa, UUID payeeUserId, String payeeName, MoneyDto amount, String note,
                                        QrType type, Instant expiresAt) {
}
