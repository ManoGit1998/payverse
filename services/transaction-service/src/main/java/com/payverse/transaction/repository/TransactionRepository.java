package com.payverse.transaction.repository;

import com.payverse.transaction.entity.Transaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID>, JpaSpecificationExecutor<Transaction> {

    Optional<Transaction> findByPayerUserIdAndIdempotencyKey(UUID payerUserId, String idempotencyKey);

    /** Unfinished sagas due for another attempt, or silent for too long (e.g. the instance crashed mid-saga). */
    @Query("""
            select t.id from Transaction t
            where t.status in (com.payverse.transaction.entity.TransactionStatus.INITIATED,
                               com.payverse.transaction.entity.TransactionStatus.PENDING)
              and ((t.nextAttemptAt is not null and t.nextAttemptAt <= :now)
                   or (t.nextAttemptAt is null and t.updatedAt < :staleBefore))
            order by t.updatedAt
            """)
    List<UUID> findDueForRecovery(@Param("now") Instant now, @Param("staleBefore") Instant staleBefore, Pageable pageable);
}
