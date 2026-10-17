package com.payverse.bill.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record PayBillRequest(@NotBlank @Size(max = 40) String billerCode, @NotBlank @Size(max = 40) String accountRef,
                             @NotNull @DecimalMin("0.01") @Digits(integer = 7, fraction = 2) BigDecimal amount) {
}
