package com.payverse.rewards.service;

import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.common.web.audit.AuditLogger;
import com.payverse.rewards.entity.Coupon;
import com.payverse.rewards.entity.CouponRedemption;
import com.payverse.rewards.entity.Reward;
import com.payverse.rewards.entity.RewardSource;
import com.payverse.rewards.exception.RewardsErrorCode;
import com.payverse.rewards.repository.CouponRedemptionRepository;
import com.payverse.rewards.repository.CouponRepository;
import com.payverse.rewards.repository.RewardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Cashback coupons: one redemption per user, a global usage limit, a validity window. */
@Service
public class CouponService {

    public record NewCoupon(String code, String description, Money cashback, Instant validFrom, Instant validUntil, int usageLimit) {
    }

    private final CouponRepository coupons;
    private final CouponRedemptionRepository redemptions;
    private final RewardRepository rewards;
    private final AuditLogger audit;
    private final Clock clock;

    public CouponService(CouponRepository coupons, CouponRedemptionRepository redemptions, RewardRepository rewards, AuditLogger audit,
                         Clock clock) {
        this.coupons = coupons;
        this.redemptions = redemptions;
        this.rewards = rewards;
        this.audit = audit;
        this.clock = clock;
    }

    /** Coupons the user can still redeem. */
    @Transactional(readOnly = true)
    public List<Coupon> available(UUID userId) {
        Set<UUID> used = redemptions.findByUserId(userId).stream().map(CouponRedemption::getCouponId).collect(Collectors.toSet());
        return coupons.findRedeemable(clock.instant()).stream().filter(c -> !used.contains(c.getId())).toList();
    }

    /** Locks the coupon row, so the usage limit holds under concurrent redemptions. */
    @Transactional
    public Reward redeem(UUID userId, String rawCode) {
        Coupon coupon = coupons.lockByCode(Coupon.normalize(rawCode))
                .orElseThrow(() -> new PayverseException(RewardsErrorCode.REWARDS_COUPON_NOT_FOUND, null));
        Instant now = clock.instant();
        Coupon.Problem problem = coupon.problemAt(now);
        if (problem != null) {
            throw new PayverseException(switch (problem) {
                case INACTIVE -> RewardsErrorCode.REWARDS_COUPON_INACTIVE;
                case NOT_STARTED -> RewardsErrorCode.REWARDS_COUPON_NOT_STARTED;
                case EXPIRED -> RewardsErrorCode.REWARDS_COUPON_EXPIRED;
                case EXHAUSTED -> RewardsErrorCode.REWARDS_COUPON_EXHAUSTED;
            }, null);
        }
        if (redemptions.existsByCouponIdAndUserId(coupon.getId(), userId)) {
            throw new PayverseException(RewardsErrorCode.REWARDS_COUPON_ALREADY_REDEEMED, null);
        }
        coupon.redeem(now);
        Reward reward = rewards.save(Reward.pending(userId, "COUPON_" + coupon.getCode(), RewardSource.COUPON, coupon.getId().toString(),
                coupon.getCashbackMoney(), now));
        redemptions.save(CouponRedemption.of(coupon.getId(), userId, reward.getId(), now));
        return reward;
    }

    @Transactional(readOnly = true)
    public List<Coupon> all() {
        return coupons.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public Coupon create(String actor, NewCoupon request) {
        Coupon coupon;
        try {
            coupon = Coupon.create(request.code(), request.description(), request.cashback(), request.validFrom(), request.validUntil(),
                    request.usageLimit(), clock.instant());
        } catch (IllegalArgumentException e) {
            throw new PayverseException(RewardsErrorCode.REWARDS_INVALID_COUPON, e.getMessage());
        }
        if (coupons.existsByCode(coupon.getCode())) {
            throw new PayverseException(RewardsErrorCode.REWARDS_COUPON_EXISTS, null);
        }
        coupons.save(coupon);
        audit.record(actor, "COUPON_CREATED", "Coupon", coupon.getId().toString(),
                Map.of("code", coupon.getCode(), "cashback", coupon.getCashback().toPlainString(), "usageLimit", coupon.getUsageLimit()));
        return coupon;
    }

    @Transactional
    public Coupon setActive(String actor, String code, boolean active) {
        Coupon coupon = coupons.findByCode(Coupon.normalize(code))
                .orElseThrow(() -> new PayverseException(RewardsErrorCode.REWARDS_COUPON_NOT_FOUND, null));
        coupon.setActive(active);
        audit.record(actor, active ? "COUPON_ACTIVATED" : "COUPON_DEACTIVATED", "Coupon", coupon.getId().toString(), Map.of("code", coupon.getCode()));
        return coupon;
    }
}
