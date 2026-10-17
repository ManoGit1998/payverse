package com.payverse.fraud.service;

import com.payverse.common.error.PayverseException;
import com.payverse.common.web.audit.AuditLogger;
import com.payverse.fraud.entity.RiskAssessment;
import com.payverse.fraud.entity.RiskRuleConfig;
import com.payverse.fraud.exception.FraudErrorCode;
import com.payverse.fraud.repository.RiskAssessmentRepository;
import com.payverse.fraud.repository.RiskRuleConfigRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Admin: tune rule weights and thresholds (audited), browse assessments. */
@Service
public class RiskRuleService {

    private final RiskRuleConfigRepository configs;
    private final RiskAssessmentRepository assessments;
    private final AuditLogger audit;
    private final Clock clock;

    public RiskRuleService(RiskRuleConfigRepository configs, RiskAssessmentRepository assessments, AuditLogger audit, Clock clock) {
        this.configs = configs;
        this.assessments = assessments;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<RiskRuleConfig> rules() {
        return configs.findAllByOrderByCodeAsc();
    }

    @Transactional
    public RiskRuleConfig update(String actor, String code, RiskRuleConfig.Settings settings) {
        RiskRuleConfig config = configs.findByCode(code).orElseThrow(() -> new PayverseException(FraudErrorCode.FRAUD_RULE_NOT_FOUND, null));
        try {
            config.update(settings, clock.instant());
        } catch (IllegalArgumentException e) {
            throw new PayverseException(FraudErrorCode.FRAUD_INVALID_RULE, e.getMessage());
        }
        Map<String, Object> details = new HashMap<>();
        details.put("code", code);
        details.put("weight", settings.weight());
        details.put("enabled", settings.enabled());
        audit.record(actor, "RISK_RULE_UPDATED", "RiskRule", config.getId().toString(), details);
        return config;
    }

    @Transactional(readOnly = true)
    public Page<RiskAssessment> assessments(UUID userId, int page, int size) {
        return userId == null ? assessments.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size))
                : assessments.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size));
    }
}
