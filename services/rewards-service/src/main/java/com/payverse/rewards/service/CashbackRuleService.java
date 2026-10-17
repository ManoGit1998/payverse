package com.payverse.rewards.service;

import com.payverse.common.error.PayverseException;
import com.payverse.common.web.audit.AuditLogger;
import com.payverse.rewards.entity.CashbackRule;
import com.payverse.rewards.exception.RewardsErrorCode;
import com.payverse.rewards.repository.CashbackRuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Admin management of cashback rules (audited). */
@Service
public class CashbackRuleService {

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{2,39}$");

    private final CashbackRuleRepository rules;
    private final AuditLogger audit;
    private final Clock clock;

    public CashbackRuleService(CashbackRuleRepository rules, AuditLogger audit, Clock clock) {
        this.rules = rules;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CashbackRule> all() {
        return rules.findAllByOrderByTriggerAscPriorityDescCodeAsc();
    }

    @Transactional
    public CashbackRule create(String actor, String code, CashbackRule.Definition definition) {
        if (code == null || !CODE.matcher(code).matches() || code.startsWith("COUPON_") || code.startsWith("REFERRAL_")) {
            throw new PayverseException(RewardsErrorCode.REWARDS_INVALID_RULE,
                    "Codes are 3-40 upper-case letters, digits and _ (COUPON_ and REFERRAL_ are reserved)");
        }
        if (rules.findByCode(code).isPresent()) {
            throw new PayverseException(RewardsErrorCode.REWARDS_RULE_EXISTS, null);
        }
        CashbackRule rule = rules.save(valid(() -> CashbackRule.create(code, definition, clock.instant())));
        audit.record(actor, "CASHBACK_RULE_CREATED", "CashbackRule", rule.getId().toString(), describe(rule));
        return rule;
    }

    @Transactional
    public CashbackRule update(String actor, String code, CashbackRule.Definition definition) {
        CashbackRule rule = rules.findByCode(code).orElseThrow(() -> new PayverseException(RewardsErrorCode.REWARDS_RULE_NOT_FOUND, null));
        valid(() -> {
            rule.update(definition, clock.instant());
            return rule;
        });
        audit.record(actor, "CASHBACK_RULE_UPDATED", "CashbackRule", rule.getId().toString(), describe(rule));
        return rule;
    }

    private static CashbackRule valid(Supplier<CashbackRule> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new PayverseException(RewardsErrorCode.REWARDS_INVALID_RULE, e.getMessage());
        }
    }

    private static Map<String, Object> describe(CashbackRule rule) {
        return Map.of("code", rule.getCode(), "trigger", rule.getTrigger().name(), "rewardType", rule.getRewardType().name(),
                "active", rule.isActive(), "budget", rule.getBudget() == null ? "unlimited" : rule.getBudget().toPlainString());
    }
}
