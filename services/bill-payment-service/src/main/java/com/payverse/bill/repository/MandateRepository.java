package com.payverse.bill.repository;

import com.payverse.bill.entity.Mandate;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MandateRepository extends JpaRepository<Mandate, UUID> {

    List<Mandate> findByUserIdOrderByCreatedAtDesc(UUID userId);

    @Query("""
            select m.id from Mandate m
            where m.status = com.payverse.bill.entity.MandateStatus.ACTIVE and m.nextRunAt <= :now order by m.nextRunAt
            """)
    List<UUID> findDue(@Param("now") Instant now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Mandate m where m.id = :id")
    Optional<Mandate> lockById(@Param("id") UUID id);
}
