package com.payverse.rewards.repository;

import com.payverse.rewards.entity.ScratchCard;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScratchCardRepository extends JpaRepository<ScratchCard, UUID> {

    long countByUserIdAndRuleCodeAndCreatedAtGreaterThanEqual(UUID userId, String ruleCode, Instant since);

    Page<ScratchCard> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    List<ScratchCard> findBySourceId(String sourceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ScratchCard c where c.id = :id")
    Optional<ScratchCard> lockById(@Param("id") UUID id);

    @Query("""
            select c.id from ScratchCard c
            where c.status = com.payverse.rewards.entity.ScratchCardStatus.UNSCRATCHED and c.expiresAt <= :now
            """)
    List<UUID> findExpired(@Param("now") Instant now, Pageable pageable);
}
