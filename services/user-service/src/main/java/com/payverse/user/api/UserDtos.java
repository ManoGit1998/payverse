package com.payverse.user.api;

import com.payverse.user.domain.Beneficiary;
import com.payverse.user.domain.IdDocumentType;
import com.payverse.user.domain.KycRecord;
import com.payverse.user.domain.KycTier;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request/response bodies of the user API. Sensitive values are only ever returned masked. */
final class UserDtos {

    private UserDtos() {
    }

    record ProfileResponse(UUID userId, String fullName, String email, LocalDate dateOfBirth, String mobileMasked,
                           KycTier kycTier, Instant createdAt, Instant updatedAt) {
    }

    record UpdateProfileRequest(@Size(min = 2, max = 100) @Schema(example = "Asha Verma") String fullName,
                                @Size(max = 254) @Schema(example = "asha@example.com") String email,
                                @Past LocalDate dateOfBirth) {
    }

    record MinKycRequest(@NotNull IdDocumentType documentType,
                         @NotBlank @Size(max = 20) @Schema(example = "ABCDE1234F") String documentNumber,
                         @NotBlank @Size(min = 2, max = 100) String fullName,
                         @NotNull @Past LocalDate dateOfBirth) {

        @Override
        public String toString() {
            return "MinKycRequest[" + documentType + " ****]";
        }
    }

    record KycRecordResponse(UUID id, KycTier tier, IdDocumentType documentType, String documentMasked,
                             KycRecord.Status status, String reason, Instant createdAt) {
    }

    record KycStatusResponse(KycTier tier, KycTier nextTier, List<KycRecordResponse> history) {
    }

    record LinkBankAccountRequest(@NotBlank @Size(max = 18) @Schema(example = "123456789012") String accountNumber,
                                  @NotBlank @Size(min = 11, max = 11) @Schema(example = "HDFC0001234") String ifsc,
                                  @NotBlank @Size(min = 2, max = 100) @Schema(example = "Asha Verma") String holderName) {

        @Override
        public String toString() {
            return "LinkBankAccountRequest[****]";
        }
    }

    record BankAccountResponse(UUID id, String maskedAccountNumber, String ifsc, String bankCode, String holderName,
                               boolean primary, Instant createdAt) {
    }

    record AddBeneficiaryRequest(@NotNull Beneficiary.Type type,
                                 @NotBlank @Size(max = 260) @Schema(example = "ravi@payverse") String value,
                                 @Size(max = 11) String ifsc,
                                 @Size(max = 50) @Schema(example = "Ravi") String nickname) {
    }

    record BeneficiaryResponse(UUID id, Beneficiary.Type type, String value, String ifsc, String nickname, Instant createdAt) {
    }

    record InternalUserResponse(UUID userId, String fullName, KycTier kycTier, String mobileMasked) {
    }

    record InternalBankAccountResponse(UUID id, UUID userId, String maskedAccountNumber, String ifsc, String holderName,
                                       boolean primary) {
    }
}
