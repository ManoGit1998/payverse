package com.payverse.bill.repository;

import com.payverse.bill.entity.MandateExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MandateExecutionRepository extends JpaRepository<MandateExecution, UUID> {

    List<MandateExecution> findByMandateIdOrderByScheduledForDesc(UUID mandateId);

    Optional<MandateExecution> findByBillPaymentId(UUID billPaymentId);
}
