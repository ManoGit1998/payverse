package com.payverse.user.model;

import com.payverse.common.error.PayverseException;
import com.payverse.user.entity.IdDocumentType;
import com.payverse.user.entity.KycTier;
import com.payverse.user.exception.UserErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdDocumentTest {

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
        assertThatThrownBy(() -> IdDocument.of(type, number))
                .isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(UserErrorCode.USER_KYC_INVALID_DOCUMENT));
    }

    @Test
    void aadhaarRequiresVerhoeffChecksum() {
        String valid = TestAadhaar.random();
        assertThat(IdDocument.of(IdDocumentType.AADHAAR, valid).masked()).startsWith("********").endsWith(valid.substring(8));
        char last = valid.charAt(11);
        String broken = valid.substring(0, 11) + (last == '9' ? '0' : (char) (last + 1));
        assertThatThrownBy(() -> IdDocument.of(IdDocumentType.AADHAAR, broken)).isInstanceOf(PayverseException.class);
        assertThat(Verhoeff.isValid("2363")).isTrue();
    }

    @Test
    void tiers() {
        assertThat(IdDocumentType.PAN.tier()).isEqualTo(KycTier.MIN_KYC);
        assertThat(IdDocumentType.AADHAAR.tier()).isEqualTo(KycTier.FULL_KYC);
        assertThat(KycTier.NONE.canUpgradeTo(KycTier.MIN_KYC)).isTrue();
        assertThat(KycTier.NONE.canUpgradeTo(KycTier.FULL_KYC)).isFalse();
        assertThat(KycTier.FULL_KYC.canUpgradeTo(KycTier.MIN_KYC)).isFalse();
    }
}
