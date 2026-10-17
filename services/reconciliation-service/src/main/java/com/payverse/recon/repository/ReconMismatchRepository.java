package com.payverse.recon.repository;

import com.payverse.recon.entity.MismatchStatus;
import com.payverse.recon.entity.ReconMismatch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReconMismatchRepository extends JpaRepository<ReconMismatch, UUID> {

    Page<ReconMismatch> findByRunIdOrderByTypeAscReferenceAsc(UUID runId, Pageable pageable);

    Page<ReconMismatch> findByStatusOrderByCreatedAtDesc(MismatchStatus status, Pageable pageable);

    long countByRunId(UUID runId);
}
