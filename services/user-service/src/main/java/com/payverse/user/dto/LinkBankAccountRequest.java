package com.payverse.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LinkBankAccountRequest(@NotBlank @Size(max = 18) @Schema(example = "123456789012") String accountNumber,
                                     @NotBlank @Size(min = 11, max = 11) @Schema(example = "HDFC0001234") String ifsc,
                                     @NotBlank @Size(min = 2, max = 100) @Schema(example = "Asha Verma") String holderName) {

    @Override
    public String toString() {
        return "LinkBankAccountRequest[****]";
    }
}
