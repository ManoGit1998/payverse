package com.payverse.rewards.repository;

import com.payverse.rewards.entity.CashbackRule;
import com.payverse.rewards.entity.RuleTrigger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CashbackRuleRepository extends JpaRepository<CashbackRule, UUID> {

    Optional<CashbackRule> findByCode(String code);

    List<CashbackRule> findByTriggerAndActiveTrueOrderByPriorityDescCodeAsc(RuleTrigger trigger);

    List<CashbackRule> findAllByOrderByTriggerAscPriorityDescCodeAsc();

    /** Atomically reserves budget; 0 rows means the budget is exhausted. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update CashbackRule r set r.spent = r.spent + :amount
            where r.id = :id and (r.budget is null or r.spent + :amount <= r.budget)
            """)
    int reserveBudget(@Param("id") UUID id, @Param("amount") BigDecimal amount);

    /** Gives budget back (voided / expired / clawed-back rewards). No-op for rule-less rewards. */
    @Modifying(flushAutomatically = true)
    @Query("update CashbackRule r set r.spent = r.spent - :amount where r.code = :code and r.spent >= :amount")
    int releaseBudget(@Param("code") String code, @Param("amount") BigDecimal amount);
}
