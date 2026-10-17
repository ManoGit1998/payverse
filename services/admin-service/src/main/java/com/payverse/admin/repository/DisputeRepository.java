package com.payverse.admin.repository;

import com.payverse.admin.entity.Dispute;
import com.payverse.admin.entity.DisputeStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DisputeRepository extends JpaRepository<Dispute, UUID> {

    List<Dispute> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Page<Dispute> findByStatusOrderByCreatedAtAsc(DisputeStatus status, Pageable pageable);

    Page<Dispute> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
