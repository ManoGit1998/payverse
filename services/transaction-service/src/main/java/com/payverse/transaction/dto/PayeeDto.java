package com.payverse.transaction.dto;

import com.payverse.transaction.entity.PayeeType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Who to pay: {@code MOBILE} (10-digit number), {@code VPA} (name@payverse) or {@code QR} (scanned upi:// payload). */
public record PayeeDto(@NotNull @Schema(example = "VPA") PayeeType type,
                       @NotBlank @Size(max = 500) @Schema(example = "ravi@payverse") String value) {
}
