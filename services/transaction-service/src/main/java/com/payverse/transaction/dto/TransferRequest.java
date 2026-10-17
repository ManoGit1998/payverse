package com.payverse.transaction.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** {@code amount} may be omitted only when paying a dynamic QR (the QR carries it). */
public record TransferRequest(@NotNull @Valid PayeeDto payee,
                              @DecimalMin("1.00") @Digits(integer = 7, fraction = 2) BigDecimal amount,
                              @Size(max = 100) String note) {
}
