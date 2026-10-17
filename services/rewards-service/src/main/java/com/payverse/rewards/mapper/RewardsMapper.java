package com.payverse.rewards.mapper;

import com.payverse.rewards.dto.CashbackRuleRequest;
import com.payverse.rewards.dto.CashbackRuleResponse;
import com.payverse.rewards.dto.CouponResponse;
import com.payverse.rewards.dto.MoneyDto;
import com.payverse.rewards.dto.ReferralResponse;
import com.payverse.rewards.dto.RewardResponse;
import com.payverse.rewards.dto.ScratchCardResponse;
import com.payverse.rewards.entity.CashbackRule;
import com.payverse.rewards.entity.Coupon;
import com.payverse.rewards.entity.Reward;
import com.payverse.rewards.entity.ScratchCard;
import com.payverse.rewards.service.ReferralService;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(imports = MoneyDto.class)
public interface RewardsMapper {

    @Mapping(target = "amount", expression = "java(card.revealedAmount().map(MoneyDto::of).orElse(null))")
    @Mapping(target = "sourceType", expression = "java(card.getSourceType().name())")
    ScratchCardResponse toResponse(ScratchCard card);

    @Mapping(target = "amount", expression = "java(MoneyDto.of(reward.getMoney()))")
    RewardResponse toResponse(Reward reward);

    @Mapping(target = "cashback", expression = "java(MoneyDto.of(coupon.getCashbackMoney()))")
    CouponResponse toResponse(Coupon coupon);

    CashbackRuleResponse toResponse(CashbackRule rule);

    @Mapping(target = "referrerBonus", expression = "java(MoneyDto.of(summary.referrerBonus()))")
    @Mapping(target = "refereeBonus", expression = "java(MoneyDto.of(summary.refereeBonus()))")
    ReferralResponse toResponse(ReferralService.ReferralSummary summary);

    default CashbackRule.Definition toDefinition(CashbackRuleRequest r) {
        return new CashbackRule.Definition(r.name(), r.trigger(), r.rewardType(), r.flatAmount(), r.percent(), r.minReward(), r.maxReward(),
                r.minTxnAmount(), r.maxTxnAmount(), Boolean.TRUE.equals(r.firstTimeOnly()), r.dailyCapPerUser(), r.activeFrom(),
                r.activeTo(), r.budget(), r.priority() == null ? 0 : r.priority(), r.active() == null || r.active());
    }
}
