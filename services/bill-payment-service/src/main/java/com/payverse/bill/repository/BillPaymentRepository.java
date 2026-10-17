package com.payverse.bill.repository;

import com.payverse.bill.entity.BillCategory;
import com.payverse.bill.entity.BillPayment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BillPaymentRepository extends JpaRepository<BillPayment, UUID> {

    Optional<BillPayment> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    Page<BillPayment> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<BillPayment> findByUserIdAndCategoryOrderByCreatedAtDesc(UUID userId, BillCategory category, Pageable pageable);

    /** Pending payments that are due for a retry, or that nobody touched for a while (crash recovery). */
    @Query("""
            select p.id from BillPayment p
            where p.status = com.payverse.bill.entity.BillPaymentStatus.PENDING
              and ((p.nextAttemptAt is not null and p.nextAttemptAt <= :now) or (p.nextAttemptAt is null and p.updatedAt <= :staleBefore))
            order by p.createdAt
            """)
    List<UUID> findRecoverable(@Param("now") Instant now, @Param("staleBefore") Instant staleBefore, Pageable pageable);
}
