package com.payverse.transaction.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Split {@code total} with participants. Without per-participant amounts it is split equally
 * (including the creator when {@code includeMe}, default true); leftover paise go to the first shares.
 */
public record CreateSplitBillRequest(@NotNull @DecimalMin("1.00") @Digits(integer = 7, fraction = 2) BigDecimal total,
                                     @Size(max = 100) String note,
                                     Boolean includeMe,
                                     @NotEmpty @Size(max = 20) List<@Valid SplitParticipant> participants) {
}
