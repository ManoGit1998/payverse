package com.payverse.payment.dto;

import com.payverse.payment.entity.PgOrderStatus;

import java.util.UUID;

public record PgChargeResponse(UUID orderId, PgOrderStatus status, String providerReference, String failureReason) {
}
