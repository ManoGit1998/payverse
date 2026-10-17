package com.payverse.payment.entity;

import com.payverse.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One charge attempt through the payment gateway (add money). Unique per idempotency key. */
@Entity
@Table(name = "pg_orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PgOrder {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String method;

    @Column(nullable = false)
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PgOrderStatus status;

    @Column(name = "provider_ref")
    private String providerRef;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static PgOrder record(UUID userId, String idempotencyKey, Money amount, String method, String provider,
                                 PgOrderStatus status, String providerRef, String failureReason, Instant now) {
        PgOrder order = new PgOrder();
        order.id = UUID.randomUUID();
        order.userId = userId;
        order.idempotencyKey = idempotencyKey;
        order.amount = amount.amount();
        order.method = method;
        order.provider = provider;
        order.status = status;
        order.providerRef = providerRef;
        order.failureReason = failureReason;
        order.createdAt = now;
        return order;
    }

    public Money getAmountMoney() {
        return Money.inr(amount);
    }
}
