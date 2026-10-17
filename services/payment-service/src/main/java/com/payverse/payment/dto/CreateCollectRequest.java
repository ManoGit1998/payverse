package com.payverse.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateCollectRequest(@NotBlank @Size(max = 80) @Schema(example = "ravi@payverse") String payerVpa,
                                   @NotNull @DecimalMin("1.00") @Digits(integer = 7, fraction = 2) BigDecimal amount,
                                   @Size(max = 100) String note) {
}
