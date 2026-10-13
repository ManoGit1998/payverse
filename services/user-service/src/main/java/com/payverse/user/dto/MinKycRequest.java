package com.payverse.user.dto;

import com.payverse.user.entity.IdDocumentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record MinKycRequest(@NotNull IdDocumentType documentType,
                            @NotBlank @Size(max = 20) @Schema(example = "ABCDE1234F") String documentNumber,
                            @NotBlank @Size(min = 2, max = 100) String fullName,
                            @NotNull @Past LocalDate dateOfBirth) {

    @Override
    public String toString() {
        return "MinKycRequest[" + documentType + " ****]";
    }
}
