package com.payverse.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record PgChargeRequest(@NotNull UUID userId,
                              @NotNull @DecimalMin("0.01") @Digits(integer = 7, fraction = 2) BigDecimal amount,
                              @NotBlank @Size(max = 20) String method,
                              @NotBlank @Size(max = 200) String idempotencyKey) {
}
