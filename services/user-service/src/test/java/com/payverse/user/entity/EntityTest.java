package com.payverse.user.entity;

import com.payverse.common.error.ErrorCode;
import com.payverse.common.error.PayverseException;
import com.payverse.user.exception.UserErrorCode;
import com.payverse.user.model.IdDocument;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityTest {

    static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");
    static final UUID USER = UUID.randomUUID();
    static final UnaryOperator<String> INDEX = v -> "idx:" + v;

    static void assertError(ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    @Test
    void profileUpdatesAndNormalises() {
        UserProfile profile = UserProfile.create(USER, null, NOW);
        profile.updateDetails("  Asha   Verma ", "Asha@Example.COM", LocalDate.of(1995, 5, 20), NOW);
        assertThat(profile.getFullName()).isEqualTo("Asha Verma");
        assertThat(profile.getEmail()).isEqualTo("asha@example.com");
        profile.attachMobile("******3210", NOW);
        profile.attachMobile("******9999", NOW);
        assertThat(profile.getMobileMasked()).isEqualTo("******3210");
    }

    @Test
    void profileRejectsBadDetails() {
        UserProfile profile = UserProfile.create(USER, null, NOW);
        assertError(() -> profile.updateDetails("A", null, null, NOW), UserErrorCode.USER_INVALID_PROFILE);
        assertError(() -> profile.updateDetails(null, "nope", null, NOW), UserErrorCode.USER_INVALID_PROFILE);
        assertError(() -> profile.updateDetails(null, null, LocalDate.of(2030, 1, 1), NOW), UserErrorCode.USER_INVALID_PROFILE);
    }

    @Test
    void kycUpgradesLockIdentity() {
        UserProfile profile = UserProfile.create(USER, null, NOW);
        assertError(() -> profile.upgradeKyc(KycTier.FULL_KYC, "Asha Verma", LocalDate.of(1995, 5, 20), NOW), UserErrorCode.USER_KYC_TIER_TRANSITION);
        assertError(() -> profile.upgradeKyc(KycTier.MIN_KYC, null, null, NOW), UserErrorCode.USER_INVALID_PROFILE);
        assertError(() -> profile.upgradeKyc(KycTier.MIN_KYC, "Young Person", LocalDate.of(2012, 1, 1), NOW), UserErrorCode.USER_KYC_VERIFICATION_FAILED);

        profile.upgradeKyc(KycTier.MIN_KYC, "Asha Verma", LocalDate.of(1995, 5, 20), NOW);
        assertThat(profile.getKycTier()).isEqualTo(KycTier.MIN_KYC);
        assertError(() -> profile.updateDetails("Someone Else", null, null, NOW), UserErrorCode.USER_INVALID_PROFILE);
        profile.updateDetails("Asha Verma", "new@example.com", null, NOW);
        profile.upgradeKyc(KycTier.FULL_KYC, null, null, NOW);
        assertThat(profile.getKycTier()).isEqualTo(KycTier.FULL_KYC);
    }

    @Test
    void kycRecordsMaskNumbers() {
        IdDocument pan = IdDocument.of(IdDocumentType.PAN, "ABCDE1234F");
        KycRecord verified = KycRecord.verified(USER, KycTier.MIN_KYC, pan, "h", null, null, NOW);
        assertThat(verified.getStatus()).isEqualTo(KycStatus.VERIFIED);
        assertThat(verified.getMaskedNumber()).isEqualTo("******234F");
        KycRecord rejected = KycRecord.rejected(USER, KycTier.MIN_KYC, pan, "h", "nope", NOW);
        assertThat(rejected.getReason()).isEqualTo("nope");
    }

    @Test
    void bankAccountsNormaliseValidateAndMask() {
        BankAccount account = BankAccount.link(USER, "1234 5678 9012", "hdfc0001234", " Asha Verma ", true, INDEX, NOW);
        assertThat(account.getAccountNumber()).isEqualTo("123456789012");
        assertThat(account.getAccountHash()).isEqualTo("idx:123456789012");
        assertThat(account.getIfsc()).isEqualTo("HDFC0001234");
        assertThat(account.getMaskedNumber()).isEqualTo("XXXXXXXX9012");
        assertThat(account.getBankCode()).isEqualTo("HDFC");
        assertThat(account.toString()).doesNotContain("123456789012");
        account.remove(NOW);
        assertThat(account.isPrimary()).isFalse();
        assertThat(account.getRemovedAt()).isEqualTo(NOW);

        assertError(() -> BankAccount.link(USER, "12345", "HDFC0001234", "Asha", true, INDEX, NOW), UserErrorCode.USER_BANK_ACCOUNT_INVALID);
        assertError(() -> BankAccount.link(USER, "123456789012", "HDFC1001234", "Asha", true, INDEX, NOW), UserErrorCode.USER_BANK_ACCOUNT_INVALID);
        assertError(() -> BankAccount.link(USER, "123456789012", "HDFC0001234", "1", true, INDEX, NOW), UserErrorCode.USER_BANK_ACCOUNT_INVALID);
    }

    @Test
    void beneficiariesNormaliseEachType() {
        assertThat(Beneficiary.save(USER, BeneficiaryType.MOBILE, "+91 98765 43210", null, "Ravi", INDEX, NOW).getValue()).isEqualTo("9876543210");
        Beneficiary vpa = Beneficiary.save(USER, BeneficiaryType.VPA, " Ravi@PayVerse ", null, null, INDEX, NOW);
        assertThat(vpa.getValue()).isEqualTo("ravi@payverse");
        assertThat(vpa.getValueHash()).isEqualTo("idx:VPA:ravi@payverse");
        Beneficiary bank = Beneficiary.save(USER, BeneficiaryType.BANK_ACCOUNT, "1234 5678 9012", "sbin0000001", "Rent", INDEX, NOW);
        assertThat(bank.getMaskedValue()).isEqualTo("********9012");
        assertThat(bank.toString()).doesNotContain("123456789012");
        bank.remove(NOW);
        assertThat(bank.getRemovedAt()).isEqualTo(NOW);
    }

    @Test
    void beneficiaryValidation() {
        assertError(() -> Beneficiary.save(USER, BeneficiaryType.MOBILE, "12345", null, null, INDEX, NOW), UserErrorCode.USER_BENEFICIARY_INVALID);
        assertError(() -> Beneficiary.save(USER, BeneficiaryType.VPA, "no-at-sign", null, null, INDEX, NOW), UserErrorCode.USER_BENEFICIARY_INVALID);
        assertError(() -> Beneficiary.save(USER, BeneficiaryType.BANK_ACCOUNT, "123456789012", null, null, INDEX, NOW), UserErrorCode.USER_BENEFICIARY_INVALID);
        assertError(() -> Beneficiary.save(USER, BeneficiaryType.MOBILE, "9876543210", null, "<script>", INDEX, NOW), UserErrorCode.USER_BENEFICIARY_INVALID);
    }
}
