package com.payverse.wallet.repository;

import com.payverse.wallet.entity.Journal;
import com.payverse.wallet.entity.JournalType;
import com.payverse.wallet.model.JournalSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface JournalRepository extends JpaRepository<Journal, UUID> {

    Optional<Journal> findByIdempotencyKey(String idempotencyKey);

    Optional<Journal> findByReversesJournalId(UUID journalId);

    /** Journals of one type created in [from, to), oldest first (reconciliation). */
    @Query(value = """
            select new com.payverse.wallet.model.JournalSummary(j.id, j.type, j.referenceType, j.referenceId, max(e.amount), j.createdAt,
                   j.reversesJournalId)
            from Journal j, LedgerEntry e
            where e.journalId = j.id and j.type = :type and j.createdAt >= :from and j.createdAt < :to
            group by j.id, j.type, j.referenceType, j.referenceId, j.createdAt, j.reversesJournalId
            order by j.createdAt, j.id
            """, countQuery = "select count(j) from Journal j where j.type = :type and j.createdAt >= :from and j.createdAt < :to")
    Page<JournalSummary> findSummaries(@Param("type") JournalType type, @Param("from") Instant from, @Param("to") Instant to,
                                       Pageable pageable);
}
