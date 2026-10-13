package com.payverse.wallet.entity;

import com.payverse.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Amount used against one limit in one period (day for debits, month for loads). */
@Entity
@Table(name = "wallet_limit_usage")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LimitUsage {

    @Id
    private UUID id;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Enumerated(EnumType.STRING)
    @Column(name = "limit_type", nullable = false)
    private LimitType limitType;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal used;

    @Version
    private long version;

    public static LimitUsage start(UUID walletId, LimitType type, LocalDate periodStart) {
        LimitUsage usage = new LimitUsage();
        usage.id = UUID.randomUUID();
        usage.walletId = walletId;
        usage.limitType = type;
        usage.periodStart = periodStart;
        usage.used = BigDecimal.ZERO.setScale(2);
        return usage;
    }

    public Money getUsedMoney() {
        return Money.inr(used);
    }

    public void add(Money amount) {
        used = getUsedMoney().plus(amount).amount();
    }
}
