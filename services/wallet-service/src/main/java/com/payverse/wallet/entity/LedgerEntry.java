package com.payverse.wallet.entity;

import com.payverse.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/** One side of a journal. Immutable (UPDATE/DELETE are rejected by a DB trigger). */
@Entity
@Table(name = "ledger_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntry {

    @Id
    private UUID id;

    @Column(name = "journal_id", nullable = false)
    private UUID journalId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntrySide side;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    /** Running balance after this entry — set for wallet accounts only. */
    @Column(name = "balance_after", precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static LedgerEntry of(UUID journalId, UUID accountId, EntrySide side, Money amount, Money balanceAfter, Instant now) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Ledger entry amounts must be positive");
        }
        LedgerEntry entry = new LedgerEntry();
        entry.id = UUID.randomUUID();
        entry.journalId = journalId;
        entry.accountId = accountId;
        entry.side = side;
        entry.amount = amount.amount();
        entry.currency = amount.currency().getCurrencyCode();
        entry.balanceAfter = balanceAfter == null ? null : balanceAfter.amount();
        entry.createdAt = now;
        return entry;
    }

    public Money getAmountMoney() {
        return Money.of(amount, Currency.getInstance(currency));
    }
}
