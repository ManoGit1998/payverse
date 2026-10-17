package com.payverse.bill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FetchBillRequest(@NotBlank @Size(max = 40) String billerCode, @NotBlank @Size(max = 40) String accountRef) {
}
