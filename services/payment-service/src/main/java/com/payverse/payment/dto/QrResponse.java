package com.payverse.payment.dto;

import com.payverse.payment.entity.QrType;

import java.time.Instant;
import java.util.UUID;

/** {@code payload} is the UPI URI encoded in the QR; {@code imageUrl} serves it as a PNG. */
public record QrResponse(UUID id, QrType type, String vpa, MoneyDto amount, String note, String payload, String imageUrl,
                         Instant expiresAt, Instant createdAt) {
}
