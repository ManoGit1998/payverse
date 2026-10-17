package com.payverse.admin.repository;

import com.payverse.admin.entity.RefundRequest;
import com.payverse.admin.entity.RefundStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RefundRequestRepository extends JpaRepository<RefundRequest, UUID> {

    Page<RefundRequest> findByStatusOrderByCreatedAtAsc(RefundStatus status, Pageable pageable);

    Page<RefundRequest> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("""
            select r.id from RefundRequest r
            where r.status in (com.payverse.admin.entity.RefundStatus.APPROVED, com.payverse.admin.entity.RefundStatus.PROCESSING)
              and r.nextAttemptAt <= :now
            order by r.nextAttemptAt
            """)
    List<UUID> findExecutable(@Param("now") Instant now, Pageable pageable);
}
