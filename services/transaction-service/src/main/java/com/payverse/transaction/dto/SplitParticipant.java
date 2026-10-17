package com.payverse.transaction.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.payverse.transaction.entity.PayeeType;

import java.math.BigDecimal;

/** A participant by MOBILE or VPA; {@code amount} for custom splits (all or none must have one). */
public record SplitParticipant(@NotNull PayeeType type, @NotBlank @Size(max = 80) String value,
                               @DecimalMin("0.01") @Digits(integer = 7, fraction = 2) BigDecimal amount) {
}
