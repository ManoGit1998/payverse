package com.payverse.rewards.controller;

import com.payverse.common.security.AuthenticatedUser;
import com.payverse.rewards.dto.CouponResponse;
import com.payverse.rewards.dto.MoneyDto;
import com.payverse.rewards.dto.PageResponse;
import com.payverse.rewards.dto.ReferralResponse;
import com.payverse.rewards.dto.RewardResponse;
import com.payverse.rewards.dto.RewardsSummaryResponse;
import com.payverse.rewards.dto.ScratchCardResponse;
import com.payverse.rewards.entity.ScratchCard;
import com.payverse.rewards.mapper.RewardsMapper;
import com.payverse.rewards.service.CouponService;
import com.payverse.rewards.service.ReferralService;
import com.payverse.rewards.service.RewardsQueryService;
import com.payverse.rewards.service.ScratchCardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Scratching and redeeming are naturally idempotent (a card is scratched once, a coupon redeemed
 * once per user) and only create a reward; the wallet credit that follows carries its own
 * Idempotency-Key ({@code reward:<id>}). So these endpoints don't take an Idempotency-Key header.
 */
@RestController
@RequestMapping("/api/v1/rewards")
@Tag(name = "Rewards")
@Validated
public class RewardsController {

    private final RewardsQueryService query;
    private final ScratchCardService scratchCards;
    private final CouponService coupons;
    private final ReferralService referrals;
    private final RewardsMapper mapper;

    public RewardsController(RewardsQueryService query, ScratchCardService scratchCards, CouponService coupons, ReferralService referrals,
                             RewardsMapper mapper) {
        this.query = query;
        this.scratchCards = scratchCards;
        this.coupons = coupons;
        this.referrals = referrals;
        this.mapper = mapper;
    }

    @Operation(summary = "My cashback totals and recent rewards")
    @GetMapping("/me")
    public RewardsSummaryResponse summary(AuthenticatedUser user) {
        RewardsQueryService.Totals totals = query.totals(user.userId());
        List<RewardResponse> recent = query.history(user.userId(), 0, 10).map(mapper::toResponse).getContent();
        return new RewardsSummaryResponse(MoneyDto.of(totals.credited()), MoneyDto.of(totals.pending()), recent);
    }

    @Operation(summary = "My rewards history")
    @GetMapping("/history")
    public PageResponse<RewardResponse> history(AuthenticatedUser user, @RequestParam(defaultValue = "0") @Min(0) int page,
                                                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var result = query.history(user.userId(), page, size);
        return new PageResponse<>(result.map(mapper::toResponse).getContent(), page, size, result.getTotalElements());
    }

    @Operation(summary = "My scratch cards (amounts hidden until scratched)")
    @GetMapping("/scratch-cards")
    public PageResponse<ScratchCardResponse> scratchCards(AuthenticatedUser user, @RequestParam(defaultValue = "0") @Min(0) int page,
                                                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Page<ScratchCard> result = scratchCards.list(user.userId(), page, size);
        return new PageResponse<>(result.map(mapper::toResponse).getContent(), page, size, result.getTotalElements());
    }

    @Operation(summary = "Scratch a card: reveals the amount; the cashback reaches the wallet within seconds")
    @PostMapping("/scratch-cards/{id}/scratch")
    public ScratchCardResponse scratch(@PathVariable UUID id, AuthenticatedUser user) {
        return mapper.toResponse(scratchCards.scratch(user.userId(), id));
    }

    @Operation(summary = "Coupons I can still redeem")
    @GetMapping("/coupons")
    public List<CouponResponse> coupons(AuthenticatedUser user) {
        return coupons.available(user.userId()).stream().map(mapper::toResponse).toList();
    }

    @Operation(summary = "Redeem a coupon for cashback (once per user)")
    @PostMapping("/coupons/{code}/redeem")
    @ResponseStatus(HttpStatus.CREATED)
    public RewardResponse redeem(@PathVariable String code, AuthenticatedUser user) {
        return mapper.toResponse(coupons.redeem(user.userId(), code));
    }

    @Operation(summary = "My referral code and stats")
    @GetMapping("/referral")
    public ReferralResponse referral(AuthenticatedUser user) {
        return mapper.toResponse(referrals.summary(user.userId()));
    }
}
