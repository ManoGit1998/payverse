package com.payverse.transaction.service;

import com.payverse.transaction.entity.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MOCK risk check that allows every transfer. Only active with
 * {@code payverse.transaction.risk-assessor=permissive}; the default is {@link RemoteRiskAssessor}.
 */
@Component
@ConditionalOnProperty(name = "payverse.transaction.risk-assessor", havingValue = "permissive")
public class PermissiveRiskAssessor implements RiskAssessor {

    private static final Logger log = LoggerFactory.getLogger(PermissiveRiskAssessor.class);

    public PermissiveRiskAssessor() {
        log.warn("PermissiveRiskAssessor (MOCK) is active: transfers are not risk-scored.");
    }

    @Override
    public Assessment assess(Transaction transaction) {
        return new Assessment(Decision.ALLOW, 0, List.of("mock: no risk rules yet"));
    }
}
