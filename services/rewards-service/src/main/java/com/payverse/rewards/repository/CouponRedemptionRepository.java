package com.payverse.rewards.repository;

import com.payverse.rewards.entity.CouponRedemption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CouponRedemptionRepository extends JpaRepository<CouponRedemption, UUID> {

    boolean existsByCouponIdAndUserId(UUID couponId, UUID userId);

    List<CouponRedemption> findByUserId(UUID userId);
}
