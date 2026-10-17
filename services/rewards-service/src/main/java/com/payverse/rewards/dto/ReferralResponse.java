package com.payverse.rewards.dto;

public record ReferralResponse(String code, long referred, long rewarded, MoneyDto referrerBonus, MoneyDto refereeBonus) {
}
