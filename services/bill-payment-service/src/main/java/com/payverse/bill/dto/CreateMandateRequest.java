package com.payverse.bill.dto;

import com.payverse.bill.entity.MandateFrequency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/** {@code amount} is required for recharge billers (no bill to fetch); bills above {@code maxAmount} are skipped. */
public record CreateMandateRequest(@NotBlank @Size(max = 40) String billerCode, @NotBlank @Size(max = 40) String accountRef,
                                   @NotNull MandateFrequency frequency,
                                   @NotNull @DecimalMin("1.00") @Digits(integer = 7, fraction = 2) BigDecimal maxAmount,
                                   @DecimalMin("1.00") @Digits(integer = 7, fraction = 2) BigDecimal amount,
                                   Instant firstRunAt) {
}
