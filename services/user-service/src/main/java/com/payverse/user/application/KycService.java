package com.payverse.user.application;

import com.payverse.common.error.PayverseException;
import com.payverse.user.application.port.DocumentStorage;
import com.payverse.user.application.port.KycRecordRepository;
import com.payverse.user.application.port.KycVerifier;
import com.payverse.user.application.port.ProfileRepository;
import com.payverse.user.application.port.UserEventPublisher;
import com.payverse.user.domain.IdDocument;
import com.payverse.user.domain.IdDocumentType;
import com.payverse.user.domain.KycRecord;
import com.payverse.user.domain.KycTier;
import com.payverse.user.domain.UserErrorCode;
import com.payverse.user.domain.UserProfile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * KYC upgrades: NONE → MIN_KYC (self-declared document) → FULL_KYC (Aadhaar + document upload).
 * Rejections are recorded and must survive the failed call, hence {@code noRollbackFor}.
 */
@Service
public class KycService {

    public static final long MAX_DOCUMENT_BYTES = 5L * 1024 * 1024;

    private final ProfileService profileService;
    private final ProfileRepository profiles;
    private final KycRecordRepository records;
    private final KycVerifier verifier;
    private final DocumentStorage storage;
    private final UserEventPublisher events;
    private final Clock clock;

    public KycService(ProfileService profileService, ProfileRepository profiles, KycRecordRepository records,
                      KycVerifier verifier, DocumentStorage storage, UserEventPublisher events, Clock clock) {
        this.profileService = profileService;
        this.profiles = profiles;
        this.records = records;
        this.verifier = verifier;
        this.storage = storage;
        this.events = events;
        this.clock = clock;
    }

    public record MinKycCommand(IdDocumentType documentType, String documentNumber, String fullName, LocalDate dateOfBirth) {
    }

    public record FullKycCommand(String aadhaarNumber, byte[] document, String contentType) {
    }

    public record KycStatus(KycTier tier, List<KycRecord> history) {
    }

    @Transactional(readOnly = true)
    public KycStatus status(UUID userId) {
        KycTier tier = profiles.find(userId).map(UserProfile::kycTier).orElse(KycTier.NONE);
        return new KycStatus(tier, records.findByUser(userId));
    }

    @Transactional(noRollbackFor = PayverseException.class)
    public UserProfile submitMinKyc(UUID userId, MinKycCommand command) {
        UserProfile profile = profileService.getOrCreate(userId);
        if (command.documentType() == null || command.documentType().tier() != KycTier.MIN_KYC) {
            throw new PayverseException(UserErrorCode.USER_KYC_INVALID_DOCUMENT, "Use PAN, VOTER_ID, DRIVING_LICENSE or PASSPORT");
        }
        IdDocument document = IdDocument.of(command.documentType(), command.documentNumber());
        Instant now = clock.instant();
        UserProfile upgraded = profile.upgradeKyc(KycTier.MIN_KYC, command.fullName(), command.dateOfBirth(), now);
        ensureNotUsedElsewhere(document, userId);
        verifyOrReject(userId, KycTier.MIN_KYC, document, upgraded);
        records.save(KycRecord.verified(userId, KycTier.MIN_KYC, document, null, null, now));
        return complete(profile, upgraded);
    }

    @Transactional(noRollbackFor = PayverseException.class)
    public UserProfile submitFullKyc(UUID userId, FullKycCommand command) {
        UserProfile profile = profileService.getOrCreate(userId);
        IdDocument aadhaar = IdDocument.of(IdDocumentType.AADHAAR, command.aadhaarNumber());
        validateUpload(command.document(), command.contentType());
        Instant now = clock.instant();
        UserProfile upgraded = profile.upgradeKyc(KycTier.FULL_KYC, null, null, now);
        ensureNotUsedElsewhere(aadhaar, userId);
        verifyOrReject(userId, KycTier.FULL_KYC, aadhaar, upgraded);
        DocumentStorage.StoredDocument stored = storage.store(userId, command.document(), command.contentType());
        records.save(KycRecord.verified(userId, KycTier.FULL_KYC, aadhaar, stored.reference(), stored.sha256(), now));
        return complete(profile, upgraded);
    }

    private void ensureNotUsedElsewhere(IdDocument document, UUID userId) {
        if (records.isVerifiedForAnotherUser(document, userId)) {
            throw new PayverseException(UserErrorCode.USER_KYC_DOCUMENT_IN_USE, null);
        }
    }

    private void verifyOrReject(UUID userId, KycTier tier, IdDocument document, UserProfile identity) {
        KycVerifier.Result result = verifier.verify(document, identity.fullName(), identity.dateOfBirth());
        if (result instanceof KycVerifier.Result.Rejected(String reason)) {
            records.save(KycRecord.rejected(userId, tier, document, reason, clock.instant()));
            throw new PayverseException(UserErrorCode.USER_KYC_VERIFICATION_FAILED, reason, Map.of("tier", tier.name()));
        }
    }

    private UserProfile complete(UserProfile before, UserProfile upgraded) {
        UserProfile saved = profiles.save(upgraded);
        events.kycUpgraded(saved.userId(), before.kycTier(), saved.kycTier());
        return saved;
    }

    /** Size limit + magic-byte check (the declared content type is not trusted). */
    static void validateUpload(byte[] content, String contentType) {
        if (content == null || content.length == 0) {
            throw new PayverseException(UserErrorCode.USER_KYC_UNSUPPORTED_FILE, "Document is empty");
        }
        if (content.length > MAX_DOCUMENT_BYTES) {
            throw new PayverseException(UserErrorCode.USER_KYC_DOCUMENT_TOO_LARGE, "Maximum size is 5 MB");
        }
        boolean jpeg = content.length > 3 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xD8 && (content[2] & 0xFF) == 0xFF;
        boolean png = content.length > 4 && (content[0] & 0xFF) == 0x89 && content[1] == 'P' && content[2] == 'N' && content[3] == 'G';
        boolean pdf = content.length > 4 && content[0] == '%' && content[1] == 'P' && content[2] == 'D' && content[3] == 'F';
        boolean declaredOk = switch (contentType == null ? "" : contentType) {
            case "image/jpeg" -> jpeg;
            case "image/png" -> png;
            case "application/pdf" -> pdf;
            default -> false;
        };
        if (!declaredOk) {
            throw new PayverseException(UserErrorCode.USER_KYC_UNSUPPORTED_FILE, "Upload a JPEG, PNG or PDF");
        }
    }
}
