package com.payverse.payment.repository;

import com.payverse.payment.entity.UpiSwitchRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UpiSwitchRecordRepository extends JpaRepository<UpiSwitchRecord, UUID> {

    Optional<UpiSwitchRecord> findByTransactionId(UUID transactionId);
}
