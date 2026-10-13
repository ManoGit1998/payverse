package com.payverse.user.dto;

import com.payverse.user.entity.BeneficiaryType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AddBeneficiaryRequest(@NotNull BeneficiaryType type,
                                    @NotBlank @Size(max = 260) @Schema(example = "ravi@payverse") String value,
                                    @Size(max = 11) String ifsc,
                                    @Size(max = 50) @Schema(example = "Ravi") String nickname) {
}
