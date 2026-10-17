package com.payverse.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record DynamicQrRequest(@NotNull @DecimalMin("1.00") @Digits(integer = 7, fraction = 2) BigDecimal amount,
                               @Size(max = 100) String note,
                               @Min(1) @Max(60) Integer validityMinutes) {
}
