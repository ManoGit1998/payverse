package com.payverse.rewards.controller;

import com.payverse.common.error.CommonErrorCode;
import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.common.security.AuthenticatedUser;
import com.payverse.rewards.dto.CashbackRuleRequest;
import com.payverse.rewards.dto.CashbackRuleResponse;
import com.payverse.rewards.dto.CouponResponse;
import com.payverse.rewards.dto.CouponStatusRequest;
import com.payverse.rewards.dto.CreateCouponRequest;
import com.payverse.rewards.mapper.RewardsMapper;
import com.payverse.rewards.service.CashbackRuleService;
import com.payverse.rewards.service.CouponService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

/** Reads need ADMIN or SUPPORT (gateway + common-security); writes need ADMIN and are audited. */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin: rewards")
public class AdminRewardsController {

    private final CashbackRuleService rules;
    private final CouponService coupons;
    private final RewardsMapper mapper;
    private final Clock clock;

    public AdminRewardsController(CashbackRuleService rules, CouponService coupons, RewardsMapper mapper, Clock clock) {
        this.rules = rules;
        this.coupons = coupons;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Operation(summary = "List cashback rules with budget usage")
    @GetMapping("/cashback-rules")
    public List<CashbackRuleResponse> rules() {
        return rules.all().stream().map(mapper::toResponse).toList();
    }

    @Operation(summary = "Create a cashback rule (ADMIN)")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/cashback-rules")
    @ResponseStatus(HttpStatus.CREATED)
    public CashbackRuleResponse create(@Valid @RequestBody CashbackRuleRequest request, AuthenticatedUser user) {
        if (request.code() == null) {
            throw new PayverseException(CommonErrorCode.VALIDATION_FAILED, "code is required");
        }
        return mapper.toResponse(rules.create(user.subject(), request.code(), mapper.toDefinition(request)));
    }

    @Operation(summary = "Replace a cashback rule's definition (ADMIN); budget already spent is kept")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/cashback-rules/{code}")
    public CashbackRuleResponse update(@PathVariable String code, @Valid @RequestBody CashbackRuleRequest request, AuthenticatedUser user) {
        return mapper.toResponse(rules.update(user.subject(), code, mapper.toDefinition(request)));
    }

    @Operation(summary = "List coupons")
    @GetMapping("/coupons")
    public List<CouponResponse> coupons() {
        return coupons.all().stream().map(mapper::toResponse).toList();
    }

    @Operation(summary = "Create a coupon (ADMIN)")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/coupons")
    @ResponseStatus(HttpStatus.CREATED)
    public CouponResponse createCoupon(@Valid @RequestBody CreateCouponRequest request, AuthenticatedUser user) {
        return mapper.toResponse(coupons.create(user.subject(), new CouponService.NewCoupon(request.code(), request.description(),
                Money.inr(request.cashback()), request.validFrom() == null ? clock.instant() : request.validFrom(), request.validUntil(),
                request.usageLimit())));
    }

    @Operation(summary = "Activate or deactivate a coupon (ADMIN)")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/coupons/{code}/status")
    public CouponResponse couponStatus(@PathVariable String code, @Valid @RequestBody CouponStatusRequest request, AuthenticatedUser user) {
        return mapper.toResponse(coupons.setActive(user.subject(), code, request.active()));
    }
}
