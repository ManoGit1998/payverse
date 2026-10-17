package com.payverse.fraud.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** The (immutable) risk verdict for one transaction. */
@Entity
@Table(name = "risk_assessments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RiskAssessment {

    @Id
    private UUID id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "payee_user_id", updatable = false)
    private UUID payeeUserId;

    @Column(name = "txn_type", nullable = false, updatable = false)
    private String txnType;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "device_id", updatable = false)
    private String deviceId;

    @Column(nullable = false, updatable = false)
    private int score;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Decision decision;

    @Column(nullable = false, updatable = false)
    private String reasons;

    @Column(name = "model_version", nullable = false, updatable = false)
    private String modelVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static RiskAssessment record(UUID transactionId, UUID userId, UUID payeeUserId, String type, BigDecimal amount, String currency,
                                        String deviceId, int score, Decision decision, List<String> reasons, String modelVersion, Instant now) {
        RiskAssessment a = new RiskAssessment();
        a.id = UUID.randomUUID();
        a.transactionId = transactionId;
        a.userId = userId;
        a.payeeUserId = payeeUserId;
        a.txnType = type;
        a.amount = amount;
        a.currency = currency;
        a.deviceId = deviceId;
        a.score = Math.max(0, Math.min(100, score));
        a.decision = decision;
        a.reasons = String.join(",", reasons);
        a.modelVersion = modelVersion;
        a.createdAt = now;
        return a;
    }

    public List<String> reasonList() {
        return reasons.isEmpty() ? List.of() : Arrays.asList(reasons.split(","));
    }
}
