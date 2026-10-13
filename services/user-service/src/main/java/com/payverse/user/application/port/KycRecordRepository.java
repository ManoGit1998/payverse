package com.payverse.user.application.port;

import com.payverse.user.domain.IdDocument;
import com.payverse.user.domain.KycRecord;

import java.util.List;
import java.util.UUID;

public interface KycRecordRepository {

    KycRecord save(KycRecord record);

    List<KycRecord> findByUser(UUID userId);

    /** True when the document is VERIFIED for a different user (one document → one account). */
    boolean isVerifiedForAnotherUser(IdDocument document, UUID userId);
}
