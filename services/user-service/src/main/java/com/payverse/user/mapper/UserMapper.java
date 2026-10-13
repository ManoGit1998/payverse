package com.payverse.user.mapper;

import com.payverse.user.dto.BankAccountResponse;
import com.payverse.user.dto.BeneficiaryResponse;
import com.payverse.user.dto.InternalBankAccountResponse;
import com.payverse.user.dto.InternalUserResponse;
import com.payverse.user.dto.KycRecordResponse;
import com.payverse.user.dto.KycStatusResponse;
import com.payverse.user.dto.ProfileResponse;
import com.payverse.user.entity.BankAccount;
import com.payverse.user.entity.Beneficiary;
import com.payverse.user.entity.KycRecord;
import com.payverse.user.entity.KycTier;
import com.payverse.user.entity.UserProfile;
import com.payverse.user.service.KycService;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.Arrays;
import java.util.List;

/** Entities → API responses (MapStruct). Only masked forms of account / document numbers leave the service. */
@Mapper
public interface UserMapper {

    ProfileResponse toResponse(UserProfile profile);

    @Mapping(target = "documentType", source = "idType")
    @Mapping(target = "documentMasked", source = "maskedNumber")
    KycRecordResponse toResponse(KycRecord record);

    List<KycRecordResponse> toKycRecordResponses(List<KycRecord> records);

    default KycStatusResponse toResponse(KycService.KycOverview overview) {
        KycTier next = Arrays.stream(KycTier.values()).filter(overview.tier()::canUpgradeTo).findFirst().orElse(null);
        return new KycStatusResponse(overview.tier(), next, toKycRecordResponses(overview.history()));
    }

    @Mapping(target = "maskedAccountNumber", source = "maskedNumber")
    BankAccountResponse toResponse(BankAccount account);

    List<BankAccountResponse> toBankAccountResponses(List<BankAccount> accounts);

    @Mapping(target = "value", source = "maskedValue")
    BeneficiaryResponse toResponse(Beneficiary beneficiary);

    List<BeneficiaryResponse> toBeneficiaryResponses(List<Beneficiary> beneficiaries);

    InternalUserResponse toInternal(UserProfile profile);

    @Mapping(target = "maskedAccountNumber", source = "maskedNumber")
    InternalBankAccountResponse toInternal(BankAccount account);
}
