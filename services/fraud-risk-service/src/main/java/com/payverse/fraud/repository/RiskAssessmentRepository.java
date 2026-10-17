package com.payverse.fraud.repository;

import com.payverse.fraud.entity.RiskAssessment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, UUID> {

    Optional<RiskAssessment> findByTransactionId(UUID transactionId);

    Page<RiskAssessment> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<RiskAssessment> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
