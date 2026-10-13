package com.payverse.user.repository;

import com.payverse.user.entity.KycRecord;
import com.payverse.user.entity.KycStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface KycRecordRepository extends JpaRepository<KycRecord, UUID> {

    List<KycRecord> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** True when the document (blind index) is verified for a different user. */
    boolean existsByIdNumberHashAndStatusAndUserIdNot(String idNumberHash, KycStatus status, UUID userId);
}
