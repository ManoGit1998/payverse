package com.payverse.fraud.dto;

import com.payverse.fraud.entity.FlagStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code freezeWallet} only with CONFIRMED_FRAUD. */
public record ResolveFlagRequest(@NotNull FlagStatus resolution, @Size(max = 500) String note, Boolean freezeWallet) {
}
