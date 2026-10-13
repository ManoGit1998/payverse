package com.payverse.wallet.entity;

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

import java.time.Instant;
import java.util.UUID;

/** A ledger account: one per wallet, plus the {@link SystemAccount}s. */
@Entity
@Table(name = "ledger_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerAccount {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false)
    private OwnerType ownerType;

    @Column(name = "owner_id")
    private UUID ownerId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static LedgerAccount forWallet(UUID walletId, UUID userId, Instant now) {
        LedgerAccount account = new LedgerAccount();
        account.id = UUID.randomUUID();
        account.code = "WALLET:" + walletId;
        account.type = AccountType.LIABILITY;
        account.ownerType = OwnerType.USER;
        account.ownerId = userId;
        account.currency = "INR";
        account.createdAt = now;
        return account;
    }
}
