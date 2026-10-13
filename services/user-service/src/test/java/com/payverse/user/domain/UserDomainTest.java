package com.payverse.user.domain;

import com.payverse.common.error.ErrorCode;
import com.payverse.common.error.PayverseException;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserDomainTest {

    static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");
    static final UUID USER = UUID.randomUUID();

    static void assertError(ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    @Nested
    class KycTiers {

        @Test
        void upgradesOneStepAtATime() {
            assertThat(KycTier.NONE.canUpgradeTo(KycTier.MIN_KYC)).isTrue();
            assertThat(KycTier.MIN_KYC.canUpgradeTo(KycTier.FULL_KYC)).isTrue();
            assertThat(KycTier.NONE.canUpgradeTo(KycTier.FULL_KYC)).isFalse();
            assertThat(KycTier.FULL_KYC.canUpgradeTo(KycTier.MIN_KYC)).isFalse();
        }
    }

    @Nested
    class Documents {

        @ParameterizedTest
        @CsvSource({"PAN,abcde1234f", "VOTER_ID,ABC1234567", "DRIVING_LICENSE,MH-12 2011 0012345", "PASSPORT,K1234567"})
        void acceptsValidFormats(IdDocumentType type, String number) {
            IdDocument document = IdDocument.of(type, number);
            assertThat(document.number()).doesNotContain(" ", "-").isUpperCase();
            assertThat(document.toString()).doesNotContain(document.number());
        }

        @ParameterizedTest
        @CsvSource({"PAN,ABCD1234F", "VOTER_ID,AB12345678", "PASSPORT,12345678", "AADHAAR,123456789012", "AADHAAR,234567890123"})
        void rejectsInvalidNumbers(IdDocumentType type, String number) {
            assertError(() -> IdDocument.of(type, number), UserErrorCode.USER_KYC_INVALID_DOCUMENT);
        }

        @Test
        void aadhaarRequiresVerhoeffChecksum() {
            String valid = TestAadhaar.random();
            assertThat(IdDocument.of(IdDocumentType.AADHAAR, valid).masked()).endsWith(valid.substring(8)).startsWith("********");
            char last = valid.charAt(11);
            String broken = valid.substring(0, 11) + (last == '9' ? '0' : (char) (last + 1));
            assertError(() -> IdDocument.of(IdDocumentType.AADHAAR, broken), UserErrorCode.USER_KYC_INVALID_DOCUMENT);
            assertThat(Verhoeff.isValid("2363")).isTrue(); // textbook example: 236 → check digit 3
        }

        @Test
        void typesKnowTheirTier() {
            assertThat(IdDocumentType.PAN.tier()).isEqualTo(KycTier.MIN_KYC);
            assertThat(IdDocumentType.AADHAAR.tier()).isEqualTo(KycTier.FULL_KYC);
        }
    }

    @Nested
    class Profiles {

        UserProfile fresh() {
            return UserProfile.create(USER, "******3210", NOW);
        }

        @Test
        void updatesAndNormalisesDetails() {
            UserProfile updated = fresh().updateDetails("  Asha   Verma ", "Asha@Example.COM", LocalDate.of(1995, 5, 20), NOW);
            assertThat(updated.fullName()).isEqualTo("Asha Verma");
            assertThat(updated.email()).isEqualTo("asha@example.com");
            assertThat(updated.updateDetails(null, null, null, NOW)).isEqualTo(updated);
        }

        @Test
        void rejectsBadDetails() {
            assertError(() -> fresh().updateDetails("A", null, null, NOW), UserErrorCode.USER_INVALID_PROFILE);
            assertError(() -> fresh().updateDetails(null, "not-an-email", null, NOW), UserErrorCode.USER_INVALID_PROFILE);
            assertError(() -> fresh().updateDetails(null, null, LocalDate.of(2030, 1, 1), NOW), UserErrorCode.USER_INVALID_PROFILE);
        }

        @Test
        void kycUpgradeLocksNameAndDob() {
            UserProfile min = fresh().upgradeKyc(KycTier.MIN_KYC, "Asha Verma", LocalDate.of(1995, 5, 20), NOW);
            assertThat(min.kycTier()).isEqualTo(KycTier.MIN_KYC);
            assertError(() -> min.updateDetails("Someone Else", null, null, NOW), UserErrorCode.USER_INVALID_PROFILE);
            assertThat(min.updateDetails("Asha Verma", "new@example.com", null, NOW).email()).isEqualTo("new@example.com");
            assertThat(min.upgradeKyc(KycTier.FULL_KYC, null, null, NOW).kycTier()).isEqualTo(KycTier.FULL_KYC);
        }

        @Test
        void kycRulesAreEnforced() {
            assertError(() -> fresh().upgradeKyc(KycTier.FULL_KYC, "Asha Verma", LocalDate.of(1995, 5, 20), NOW), UserErrorCode.USER_KYC_TIER_TRANSITION);
            assertError(() -> fresh().upgradeKyc(KycTier.MIN_KYC, null, null, NOW), UserErrorCode.USER_INVALID_PROFILE);
            assertError(() -> fresh().upgradeKyc(KycTier.MIN_KYC, "Young Person", LocalDate.of(2012, 1, 1), NOW), UserErrorCode.USER_KYC_VERIFICATION_FAILED);
        }

        @Test
        void mobileCanBeFilledLater() {
            UserProfile profile = UserProfile.create(USER, null, NOW).withMobileMasked("******1111", NOW);
            assertThat(profile.mobileMasked()).isEqualTo("******1111");
        }
    }

    @Nested
    class BankAccounts {

        @Test
        void normalisesAndMasks() {
            BankAccount account = BankAccount.link(USER, "1234 5678 9012", "hdfc0001234", " Asha Verma ", true, NOW);
            assertThat(account.accountNumber()).isEqualTo("123456789012");
            assertThat(account.ifsc()).isEqualTo("HDFC0001234");
            assertThat(account.maskedNumber()).isEqualTo("XXXXXXXX9012");
            assertThat(account.bankCode()).isEqualTo("HDFC");
            assertThat(account.toString()).doesNotContain("123456789012");
            assertThat(account.withPrimary(false).primary()).isFalse();
        }

        @Test
        void validates() {
            assertError(() -> BankAccount.link(USER, "12345", "HDFC0001234", "Asha", true, NOW), UserErrorCode.USER_BANK_ACCOUNT_INVALID);
            assertError(() -> BankAccount.link(USER, "123456789012", "HDFC1001234", "Asha", true, NOW), UserErrorCode.USER_BANK_ACCOUNT_INVALID);
            assertError(() -> BankAccount.link(USER, "123456789012", "HDFC0001234", "1", true, NOW), UserErrorCode.USER_BANK_ACCOUNT_INVALID);
        }
    }

    @Nested
    class Beneficiaries {

        @Test
        void normalisesEachType() {
            assertThat(Beneficiary.save(USER, Beneficiary.Type.MOBILE, "+91 98765 43210", null, "Ravi", NOW).value()).isEqualTo("9876543210");
            assertThat(Beneficiary.save(USER, Beneficiary.Type.VPA, " Ravi@PayVerse ", null, null, NOW).value()).isEqualTo("ravi@payverse");
            Beneficiary bank = Beneficiary.save(USER, Beneficiary.Type.BANK_ACCOUNT, "1234 5678 9012", "sbin0000001", "Rent", NOW);
            assertThat(bank.ifsc()).isEqualTo("SBIN0000001");
            assertThat(bank.maskedValue()).isEqualTo("********9012");
        }

        @Test
        void validates() {
            assertError(() -> Beneficiary.save(USER, Beneficiary.Type.MOBILE, "12345", null, null, NOW), UserErrorCode.USER_BENEFICIARY_INVALID);
            assertError(() -> Beneficiary.save(USER, Beneficiary.Type.VPA, "no-at-sign", null, null, NOW), UserErrorCode.USER_BENEFICIARY_INVALID);
            assertError(() -> Beneficiary.save(USER, Beneficiary.Type.BANK_ACCOUNT, "123456789012", null, null, NOW), UserErrorCode.USER_BENEFICIARY_INVALID);
            assertError(() -> Beneficiary.save(USER, Beneficiary.Type.MOBILE, "9876543210", null, "<script>", NOW), UserErrorCode.USER_BENEFICIARY_INVALID);
        }
    }
}
