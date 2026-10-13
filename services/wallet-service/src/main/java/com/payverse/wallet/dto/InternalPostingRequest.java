package com.payverse.wallet.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Saga step from transaction-service / bill-payment-service / rewards-service. */
public record InternalPostingRequest(@NotNull @DecimalMin("0.01") @Digits(integer = 7, fraction = 2) BigDecimal amount,
                                     @NotNull Purpose purpose,
                                     @NotBlank @Size(max = 40) @Schema(example = "Transaction") String referenceType,
                                     @NotBlank @Size(max = 100) String referenceId,
                                     @Size(max = 200) String description) {

    /** Determines the contra ledger account and which limits apply. */
    public enum Purpose { TRANSFER, BILL_PAYMENT, BILL_REFUND, CASHBACK }
}
