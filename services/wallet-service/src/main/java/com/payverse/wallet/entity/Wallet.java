package com.payverse.wallet.entity;

import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.wallet.exception.WalletErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;
import java.util.UUID;

/**
 * A user's wallet. {@link #balance} is the materialised sum of its ledger account's entries
 * (credits − debits), updated in the same transaction as every posting; {@code @Version} guards
 * against lost updates and the DB {@code CHECK (balance >= 0)} against overdrafts.
 */
@Entity
@Table(name = "wallets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Wallet {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "ledger_account_id", nullable = false, unique = true)
    private UUID ledgerAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WalletStatus status;

    @Column(name = "status_reason")
    private String statusReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_tier", nullable = false)
    private KycTier kycTier;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public static Wallet open(UUID walletId, UUID userId, UUID ledgerAccountId, KycTier tier, Instant now) {
        Wallet wallet = new Wallet();
        wallet.id = walletId;
        wallet.userId = userId;
        wallet.ledgerAccountId = ledgerAccountId;
        wallet.status = WalletStatus.ACTIVE;
        wallet.kycTier = tier;
        wallet.balance = BigDecimal.ZERO.setScale(2);
        wallet.currency = Money.INR.getCurrencyCode();
        wallet.createdAt = now;
        wallet.updatedAt = now;
        return wallet;
    }

    public Money getBalanceMoney() {
        return Money.of(balance, Currency.getInstance(currency));
    }

    public void applyDebit(Money amount, Instant now) {
        Money current = getBalanceMoney();
        if (current.isLessThan(amount)) {
            throw new PayverseException(WalletErrorCode.WALLET_INSUFFICIENT_BALANCE, null,
                    Map.of("balance", current.toPlainString(), "requested", amount.toPlainString()));
        }
        balance = current.minus(amount).amount();
        updatedAt = now;
    }

    public void applyCredit(Money amount, Instant now) {
        balance = getBalanceMoney().plus(amount).amount();
        updatedAt = now;
    }

    public void ensureActive() {
        if (status == WalletStatus.FROZEN) {
            throw new PayverseException(WalletErrorCode.WALLET_FROZEN, statusReason);
        }
    }

    public boolean freeze(String reason, Instant now) {
        boolean changed = status != WalletStatus.FROZEN;
        status = WalletStatus.FROZEN;
        statusReason = reason;
        updatedAt = now;
        return changed;
    }

    public boolean unfreeze(Instant now) {
        boolean changed = status != WalletStatus.ACTIVE;
        status = WalletStatus.ACTIVE;
        statusReason = null;
        updatedAt = now;
        return changed;
    }

    /** Tiers only move up (events may arrive late or twice). */
    public boolean changeTier(KycTier tier, Instant now) {
        if (tier.ordinal() <= kycTier.ordinal()) {
            return false;
        }
        kycTier = tier;
        updatedAt = now;
        return true;
    }
}
