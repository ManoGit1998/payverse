package com.payverse.transaction.service;

import com.payverse.common.error.PayverseException;
import com.payverse.transaction.client.FraudClient;
import com.payverse.transaction.config.TransactionProperties;
import com.payverse.transaction.entity.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Asks fraud-risk-service before any money moves. If it is unreachable the policy is
 * fail-open for small amounts ({@code risk-fail-open-max}, default 2000) and fail-closed above.
 */
@Component
@ConditionalOnProperty(name = "payverse.transaction.risk-assessor", havingValue = "remote", matchIfMissing = true)
public class RemoteRiskAssessor implements RiskAssessor {

    private static final Logger log = LoggerFactory.getLogger(RemoteRiskAssessor.class);

    private final FraudClient fraud;
    private final TransactionProperties properties;

    public RemoteRiskAssessor(FraudClient fraud, TransactionProperties properties) {
        this.fraud = fraud;
        this.properties = properties;
    }

    @Override
    public Assessment assess(Transaction transaction) {
        try {
            FraudClient.AssessResponse response = fraud.assess(transaction);
            return new Assessment(Decision.valueOf(response.decision()), response.score(),
                    response.reasons() == null ? List.of() : response.reasons());
        } catch (PayverseException e) {
            boolean small = transaction.getAmount().compareTo(properties.riskFailOpenMax()) <= 0;
            log.warn("Risk assessment unavailable for {} ({}): {}", transaction.getId(), e.errorCode().code(),
                    small ? "allowing (fail-open)" : "blocking (fail-closed)");
            return small ? new Assessment(Decision.ALLOW, 0, List.of("RISK_UNAVAILABLE_FAIL_OPEN"))
                    : new Assessment(Decision.BLOCK, 100, List.of("RISK_UNAVAILABLE_FAIL_CLOSED"));
        }
    }
}
