package com.payverse.user.api;

import com.payverse.user.application.KycService;
import com.payverse.user.domain.BankAccount;
import com.payverse.user.domain.Beneficiary;
import com.payverse.user.domain.KycRecord;
import com.payverse.user.domain.KycTier;
import com.payverse.user.domain.UserProfile;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.Arrays;
import java.util.List;

/** Domain records → API responses. Only masked forms of account numbers / documents leave the service. */
@Mapper
interface UserApiMapper {

    UserDtos.ProfileResponse toResponse(UserProfile profile);

    @Mapping(target = "documentType", expression = "java(record.document().type())")
    @Mapping(target = "documentMasked", expression = "java(record.document().masked())")
    UserDtos.KycRecordResponse toResponse(KycRecord record);

    List<UserDtos.KycRecordResponse> toKycRecordResponses(List<KycRecord> records);

    default UserDtos.KycStatusResponse toResponse(KycService.KycStatus status) {
        KycTier next = Arrays.stream(KycTier.values()).filter(status.tier()::canUpgradeTo).findFirst().orElse(null);
        return new UserDtos.KycStatusResponse(status.tier(), next, toKycRecordResponses(status.history()));
    }

    @Mapping(target = "maskedAccountNumber", expression = "java(account.maskedNumber())")
    @Mapping(target = "bankCode", expression = "java(account.bankCode())")
    UserDtos.BankAccountResponse toResponse(BankAccount account);

    List<UserDtos.BankAccountResponse> toBankAccountResponses(List<BankAccount> accounts);

    @Mapping(target = "value", expression = "java(beneficiary.maskedValue())")
    UserDtos.BeneficiaryResponse toResponse(Beneficiary beneficiary);

    List<UserDtos.BeneficiaryResponse> toBeneficiaryResponses(List<Beneficiary> beneficiaries);

    UserDtos.InternalUserResponse toInternal(UserProfile profile);

    @Mapping(target = "maskedAccountNumber", expression = "java(account.maskedNumber())")
    UserDtos.InternalBankAccountResponse toInternal(BankAccount account);
}
