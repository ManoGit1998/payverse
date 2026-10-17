package com.payverse.transaction.service;

import com.payverse.transaction.entity.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MOCK risk check: allows every transfer. Replaced by a client for fraud-risk-service in Phase 6
 * (velocity checks, amount anomalies, new devices).
 */
@Component
public class PermissiveRiskAssessor implements RiskAssessor {

    private static final Logger log = LoggerFactory.getLogger(PermissiveRiskAssessor.class);

    public PermissiveRiskAssessor() {
        log.warn("PermissiveRiskAssessor (MOCK) is active: transfers are not risk-scored until fraud-risk-service (Phase 6).");
    }

    @Override
    public Assessment assess(Transaction transaction) {
        return new Assessment(Decision.ALLOW, 0, List.of("mock: no risk rules yet"));
    }
}
