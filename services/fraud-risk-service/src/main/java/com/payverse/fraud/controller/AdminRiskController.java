package com.payverse.fraud.controller;

import com.payverse.common.security.AuthenticatedUser;
import com.payverse.fraud.dto.AssessmentResponse;
import com.payverse.fraud.dto.FlagResponse;
import com.payverse.fraud.dto.PageResponse;
import com.payverse.fraud.dto.ResolveFlagRequest;
import com.payverse.fraud.dto.RiskRuleResponse;
import com.payverse.fraud.dto.RiskRuleUpdateRequest;
import com.payverse.fraud.entity.FlagStatus;
import com.payverse.fraud.entity.RiskRuleConfig;
import com.payverse.fraud.mapper.FraudMapper;
import com.payverse.fraud.service.FlagService;
import com.payverse.fraud.service.RiskRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** ADMIN and SUPPORT can review and resolve flags; only ADMIN freezes wallets or tunes rules. */
@RestController
@RequestMapping("/api/v1/admin/risk")
@Tag(name = "Admin: risk")
@Validated
public class AdminRiskController {

    private final FlagService flags;
    private final RiskRuleService rules;
    private final FraudMapper mapper;

    public AdminRiskController(FlagService flags, RiskRuleService rules, FraudMapper mapper) {
        this.flags = flags;
        this.rules = rules;
        this.mapper = mapper;
    }

    @Operation(summary = "Flags (default: OPEN)")
    @GetMapping("/flags")
    public PageResponse<FlagResponse> flags(@RequestParam(defaultValue = "OPEN") FlagStatus status,
                                            @RequestParam(defaultValue = "0") @Min(0) int page,
                                            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var result = flags.list(status, page, size);
        return new PageResponse<>(result.map(mapper::toResponse).getContent(), page, size, result.getTotalElements());
    }

    @Operation(summary = "Resolve a flag as CONFIRMED_FRAUD (optionally freezing the wallet, ADMIN only) or FALSE_POSITIVE")
    @PostMapping("/flags/{id}/resolve")
    public FlagResponse resolve(@PathVariable UUID id, @Valid @RequestBody ResolveFlagRequest request, AuthenticatedUser user) {
        boolean freeze = Boolean.TRUE.equals(request.freezeWallet());
        if (freeze && !user.hasRole("ADMIN")) {
            throw new AccessDeniedException("Only ADMIN can freeze wallets");
        }
        return mapper.toResponse(flags.resolve(user.subject(), id, request.resolution(), request.note(), freeze));
    }

    @Operation(summary = "Risk rules with their weights and thresholds")
    @GetMapping("/rules")
    public List<RiskRuleResponse> rules() {
        return rules.rules().stream().map(mapper::toResponse).toList();
    }

    @Operation(summary = "Tune a rule (ADMIN)")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/rules/{code}")
    public RiskRuleResponse update(@PathVariable String code, @Valid @RequestBody RiskRuleUpdateRequest r, AuthenticatedUser user) {
        return mapper.toResponse(rules.update(user.subject(), code, new RiskRuleConfig.Settings(r.weight(), r.enabled(), r.thresholdAmount(),
                r.thresholdCount(), r.windowSeconds(), r.multiplier())));
    }

    @Operation(summary = "Assessments, newest first (optionally for one user)")
    @GetMapping("/assessments")
    public PageResponse<AssessmentResponse> assessments(@RequestParam(required = false) UUID userId,
                                                        @RequestParam(defaultValue = "0") @Min(0) int page,
                                                        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var result = rules.assessments(userId, page, size);
        return new PageResponse<>(result.map(mapper::toResponse).getContent(), page, size, result.getTotalElements());
    }
}
