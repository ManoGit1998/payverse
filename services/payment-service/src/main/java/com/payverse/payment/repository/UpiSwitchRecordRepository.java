package com.payverse.payment.repository;

import com.payverse.payment.entity.UpiSwitchRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UpiSwitchRecordRepository extends JpaRepository<UpiSwitchRecord, UUID> {

    Optional<UpiSwitchRecord> findByTransactionId(UUID transactionId);

    Page<UpiSwitchRecord> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAscIdAsc(Instant from, Instant to,
                                                                                                    Pageable pageable);
}
