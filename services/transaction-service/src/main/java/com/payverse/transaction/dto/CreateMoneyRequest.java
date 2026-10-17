package com.payverse.transaction.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Ask someone (by MOBILE or VPA) for money. */
public record CreateMoneyRequest(@NotNull @Valid PayeeDto payer,
                                 @NotNull @DecimalMin("1.00") @Digits(integer = 7, fraction = 2) BigDecimal amount,
                                 @Size(max = 100) String note) {
}
