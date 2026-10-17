package com.payverse.rewards.repository;

import com.payverse.rewards.entity.Coupon;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CouponRepository extends JpaRepository<Coupon, UUID> {

    Optional<Coupon> findByCode(String code);

    boolean existsByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Coupon c where c.code = :code")
    Optional<Coupon> lockByCode(@Param("code") String code);

    @Query("""
            select c from Coupon c
            where c.active = true and c.validFrom <= :now and c.validUntil > :now and c.usedCount < c.usageLimit
            order by c.validUntil
            """)
    List<Coupon> findRedeemable(@Param("now") Instant now);

    List<Coupon> findAllByOrderByCreatedAtDesc();
}
