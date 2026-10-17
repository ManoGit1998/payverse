package com.payverse.rewards.exception;

import com.payverse.common.error.ErrorCode;

/** Error codes owned by rewards-service. */
public enum RewardsErrorCode implements ErrorCode {

    REWARDS_INVALID_RULE(400, "Invalid cashback rule"),
    REWARDS_INVALID_COUPON(400, "Invalid coupon"),
    REWARDS_SCRATCH_CARD_NOT_FOUND(404, "Scratch card not found"),
    REWARDS_COUPON_NOT_FOUND(404, "Coupon not found"),
    REWARDS_RULE_NOT_FOUND(404, "Cashback rule not found"),
    REWARDS_SCRATCH_CARD_EXPIRED(409, "Scratch card has expired"),
    REWARDS_SCRATCH_CARD_VOIDED(409, "Scratch card was voided"),
    REWARDS_COUPON_INACTIVE(409, "Coupon is not active"),
    REWARDS_COUPON_NOT_STARTED(409, "Coupon is not valid yet"),
    REWARDS_COUPON_EXPIRED(409, "Coupon has expired"),
    REWARDS_COUPON_EXHAUSTED(409, "Coupon has been fully redeemed"),
    REWARDS_COUPON_ALREADY_REDEEMED(409, "You have already redeemed this coupon"),
    REWARDS_COUPON_EXISTS(409, "A coupon with this code already exists"),
    REWARDS_RULE_EXISTS(409, "A rule with this code already exists");

    private final int httpStatus;
    private final String title;

    RewardsErrorCode(int httpStatus, String title) {
        this.httpStatus = httpStatus;
        this.title = title;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }

    @Override
    public String title() {
        return title;
    }
}
