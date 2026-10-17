package com.payverse.rewards.repository;

import com.payverse.rewards.entity.Reward;
import com.payverse.rewards.entity.RewardSource;
import com.payverse.rewards.entity.RewardStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RewardRepository extends JpaRepository<Reward, UUID> {

    boolean existsByUserIdAndRuleCodeAndSourceTypeAndSourceId(UUID userId, String ruleCode, RewardSource sourceType, String sourceId);

    Optional<Reward> findByUserIdAndRuleCodeAndSourceTypeAndSourceId(UUID userId, String ruleCode, RewardSource sourceType, String sourceId);

    long countByUserIdAndRuleCodeAndCreatedAtGreaterThanEqual(UUID userId, String ruleCode, Instant since);

    List<Reward> findBySourceIdIn(List<String> sourceIds);

    Page<Reward> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    @Query("select coalesce(sum(r.amount), 0) from Reward r where r.userId = :userId and r.status = :status")
    BigDecimal sumByUserAndStatus(@Param("userId") UUID userId, @Param("status") RewardStatus status);

    @Query("""
            select r.id from Reward r
            where r.status = com.payverse.rewards.entity.RewardStatus.PENDING_CREDIT and r.nextAttemptAt <= :now
            order by r.nextAttemptAt
            """)
    List<UUID> findDue(@Param("now") Instant now, Pageable pageable);
}
