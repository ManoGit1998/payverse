package com.payverse.fraud.service;

import com.payverse.fraud.entity.RiskRuleConfig;
import com.payverse.fraud.model.RiskContext;

/**
 * One risk signal. Implementations hold the logic; thresholds and weights come from the
 * {@code risk_rules} row with the same {@link #code()}, so admins can tune them without a deploy.
 */
public interface RiskRule {

    String code();

    boolean triggered(RiskContext context, RiskRuleConfig config);
}
