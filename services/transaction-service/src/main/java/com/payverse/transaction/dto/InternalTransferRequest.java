package com.payverse.transaction.dto;

import com.payverse.transaction.entity.TransferSource;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/** From payment-service when a payer approves a UPI collect request. */
public record InternalTransferRequest(@NotNull UUID payerUserId,
                                      @NotNull UUID payeeUserId,
                                      @Size(max = 80) String payeeRef,
                                      @NotNull @DecimalMin("0.01") @Digits(integer = 7, fraction = 2) BigDecimal amount,
                                      @Size(max = 100) String note,
                                      @NotNull TransferSource source,
                                      UUID sourceId) {
}
