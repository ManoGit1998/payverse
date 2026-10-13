package com.payverse.user.infrastructure.persistence;

import com.payverse.common.web.crypto.BlindIndexer;
import com.payverse.user.application.port.KycRecordRepository;
import com.payverse.user.domain.IdDocument;
import com.payverse.user.domain.IdDocumentType;
import com.payverse.user.domain.KycRecord;
import com.payverse.user.domain.KycTier;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
class JpaKycRecordRepository implements KycRecordRepository {

    private final KycRecordJpaRepository jpa;
    private final BlindIndexer blindIndexer;

    JpaKycRecordRepository(KycRecordJpaRepository jpa, BlindIndexer blindIndexer) {
        this.jpa = jpa;
        this.blindIndexer = blindIndexer;
    }

    @Override
    public KycRecord save(KycRecord record) {
        KycRecordEntity e = new KycRecordEntity();
        e.id = record.id();
        e.userId = record.userId();
        e.tier = record.tier().name();
        e.idType = record.document().type().name();
        e.idNumber = record.document().number();
        e.idNumberHash = hash(record.document());
        e.documentRef = record.documentRef();
        e.documentSha256 = record.documentSha256();
        e.status = record.status().name();
        e.reason = record.reason();
        e.createdAt = record.createdAt();
        jpa.save(e);
        return record;
    }

    @Override
    public List<KycRecord> findByUser(UUID userId) {
        return jpa.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(e -> new KycRecord(e.id, e.userId, KycTier.valueOf(e.tier),
                        new IdDocument(IdDocumentType.valueOf(e.idType), e.idNumber), e.documentRef, e.documentSha256,
                        KycRecord.Status.valueOf(e.status), e.reason, e.createdAt))
                .toList();
    }

    @Override
    public boolean isVerifiedForAnotherUser(IdDocument document, UUID userId) {
        return jpa.existsByIdNumberHashAndStatusAndUserIdNot(hash(document), KycRecord.Status.VERIFIED.name(), userId);
    }

    private String hash(IdDocument document) {
        return blindIndexer.index(document.type().name() + ":" + document.number());
    }
}
