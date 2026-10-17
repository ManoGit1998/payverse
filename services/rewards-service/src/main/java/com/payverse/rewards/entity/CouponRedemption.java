package com.payverse.rewards.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "coupon_redemptions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponRedemption {

    @Id
    private UUID id;

    @Column(name = "coupon_id", nullable = false)
    private UUID couponId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "reward_id", nullable = false)
    private UUID rewardId;

    @Column(name = "redeemed_at", nullable = false)
    private Instant redeemedAt;

    public static CouponRedemption of(UUID couponId, UUID userId, UUID rewardId, Instant now) {
        CouponRedemption r = new CouponRedemption();
        r.id = UUID.randomUUID();
        r.couponId = couponId;
        r.userId = userId;
        r.rewardId = rewardId;
        r.redeemedAt = now;
        return r;
    }
}
