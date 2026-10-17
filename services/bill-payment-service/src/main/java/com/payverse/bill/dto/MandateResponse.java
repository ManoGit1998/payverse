package com.payverse.bill.dto;

import com.payverse.bill.entity.MandateFrequency;
import com.payverse.bill.entity.MandateStatus;

import java.time.Instant;
import java.util.UUID;

public record MandateResponse(UUID id, String billerCode, String accountRefMasked, MandateFrequency frequency, MoneyDto maxAmount,
                              MoneyDto amount, MandateStatus status, Instant nextRunAt, int runCount, Instant createdAt, Instant cancelledAt) {
}
