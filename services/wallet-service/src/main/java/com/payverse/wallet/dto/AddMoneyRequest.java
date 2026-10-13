package com.payverse.wallet.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record AddMoneyRequest(@NotNull @DecimalMin("1.00") @Digits(integer = 7, fraction = 2) @Schema(example = "500.00") BigDecimal amount,
                              @NotNull PaymentMethod paymentMethod) {

    public enum PaymentMethod { UPI, CARD, NETBANKING }
}
