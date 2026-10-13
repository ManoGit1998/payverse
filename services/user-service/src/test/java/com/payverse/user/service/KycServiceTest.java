package com.payverse.user.service;

import com.payverse.common.error.ErrorCode;
import com.payverse.common.error.PayverseException;
import com.payverse.common.web.crypto.BlindIndexer;
import com.payverse.user.entity.IdDocumentType;
import com.payverse.user.entity.KycRecord;
import com.payverse.user.entity.KycStatus;
import com.payverse.user.entity.KycTier;
import com.payverse.user.entity.UserProfile;
import com.payverse.user.exception.UserErrorCode;
import com.payverse.user.model.TestAadhaar;
import com.payverse.user.repository.KycRecordRepository;
import com.payverse.user.repository.UserProfileRepository;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KycServiceTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T10:00:00Z"), ZoneOffset.UTC);
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    final ProfileService profileService = mock(ProfileService.class);
    final UserProfileRepository profiles = mock(UserProfileRepository.class);
    final KycRecordRepository records = mock(KycRecordRepository.class);
    final UserEventPublisher events = mock(UserEventPublisher.class);
    final DocumentStorage storage = mock(DocumentStorage.class);
    final UUID userId = UUID.randomUUID();
    UserProfile profile;
    KycService service;

    @BeforeEach
    void setUp() {
        profile = UserProfile.create(userId, "******3210", CLOCK.instant());
        when(profileService.getOrCreate(userId)).thenReturn(profile);
        when(profiles.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(storage.store(any(), any(), any())).thenReturn(new DocumentStorage.StoredDocument("mock-kyc://x", "sha"));
        service = new KycService(profileService, profiles, records, new MockKycVerifier(), storage, events,
                new BlindIndexer(new byte[32]), CLOCK);
    }

    static void assertError(ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    KycService.MinKycCommand pan(String number) {
        return new KycService.MinKycCommand(IdDocumentType.PAN, number, "Asha Verma", LocalDate.of(1995, 5, 20));
    }

    @Test
    void minThenFullKyc() {
        assertThat(service.submitMinKyc(userId, pan("ABCDE1234F")).getKycTier()).isEqualTo(KycTier.MIN_KYC);
        verify(events).kycUpgraded(userId, KycTier.NONE, KycTier.MIN_KYC);
        assertThat(service.submitFullKyc(userId, new KycService.FullKycCommand(TestAadhaar.random(), PNG, "image/png")).getKycTier())
                .isEqualTo(KycTier.FULL_KYC);
        verify(events).kycUpgraded(userId, KycTier.MIN_KYC, KycTier.FULL_KYC);
        verify(storage).store(eq(userId), any(), eq("image/png"));
    }

    @Test
    void fullKycRequiresMinKycFirst() {
        assertError(() -> service.submitFullKyc(userId, new KycService.FullKycCommand(TestAadhaar.random(), PNG, "image/png")),
                UserErrorCode.USER_KYC_TIER_TRANSITION);
    }

    @Test
    void aadhaarIsNotAcceptedForMinKyc() {
        assertError(() -> service.submitMinKyc(userId, new KycService.MinKycCommand(IdDocumentType.AADHAAR, TestAadhaar.random(),
                "Asha Verma", LocalDate.of(1995, 5, 20))), UserErrorCode.USER_KYC_INVALID_DOCUMENT);
    }

    @Test
    void rejectionIsRecordedAndTierUnchanged() {
        assertError(() -> service.submitMinKyc(userId, pan("ABCDE9999F")), UserErrorCode.USER_KYC_VERIFICATION_FAILED);
        ArgumentCaptor<KycRecord> saved = ArgumentCaptor.forClass(KycRecord.class);
        verify(records).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(KycStatus.REJECTED);
        assertThat(profile.getKycTier()).isEqualTo(KycTier.NONE);
        verify(events, never()).kycUpgraded(any(), any(), any());
    }

    @Test
    void oneDocumentOneAccount() {
        when(records.existsByIdNumberHashAndStatusAndUserIdNot(anyString(), eq(KycStatus.VERIFIED), eq(userId))).thenReturn(true);
        assertError(() -> service.submitMinKyc(userId, pan("ABCDE1234F")), UserErrorCode.USER_KYC_DOCUMENT_IN_USE);
    }

    @Test
    void uploadValidation() {
        assertError(() -> KycService.validateUpload(new byte[0], "image/png"), UserErrorCode.USER_KYC_UNSUPPORTED_FILE);
        assertError(() -> KycService.validateUpload(PNG, "image/jpeg"), UserErrorCode.USER_KYC_UNSUPPORTED_FILE);
        assertError(() -> KycService.validateUpload("<html>".getBytes(), "text/html"), UserErrorCode.USER_KYC_UNSUPPORTED_FILE);
        assertError(() -> KycService.validateUpload(new byte[(int) KycService.MAX_DOCUMENT_BYTES + 1], "application/pdf"),
                UserErrorCode.USER_KYC_DOCUMENT_TOO_LARGE);
        KycService.validateUpload(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00}, "image/jpeg");
        KycService.validateUpload("%PDF-1.7".getBytes(), "application/pdf");
    }
}
