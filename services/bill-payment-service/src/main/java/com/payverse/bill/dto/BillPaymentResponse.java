package com.payverse.bill.dto;

import com.payverse.bill.entity.BillCategory;
import com.payverse.bill.entity.BillPaymentStatus;
import com.payverse.bill.entity.PaymentSource;

import java.time.Instant;
import java.util.UUID;

public record BillPaymentResponse(UUID id, String billerCode, BillCategory category, String accountRefMasked, MoneyDto amount,
                                  BillPaymentStatus status, String billerRef, boolean refunded, String failureCode, String failureReason,
                                  PaymentSource source, UUID mandateId, Instant createdAt, Instant completedAt) {
}
