package com.payverse.transaction.service;

import com.payverse.transaction.entity.Transaction;

import java.util.List;

/** Pre-transfer risk check. {@link PermissiveRiskAssessor} until fraud-risk-service arrives in Phase 6. */
public interface RiskAssessor {

    enum Decision { ALLOW, REVIEW, BLOCK }

    record Assessment(Decision decision, int score, List<String> reasons) {
    }

    Assessment assess(Transaction transaction);
}
