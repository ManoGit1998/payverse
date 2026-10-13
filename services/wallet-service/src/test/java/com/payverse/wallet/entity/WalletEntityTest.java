package com.payverse.wallet.entity;

import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.wallet.exception.WalletErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletEntityTest {

    static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");

    Wallet wallet() {
        UUID walletId = UUID.randomUUID();
        return Wallet.open(walletId, UUID.randomUUID(), UUID.randomUUID(), KycTier.MIN_KYC, NOW);
    }

    @Test
    void creditsAndDebitsAreExact() {
        Wallet wallet = wallet();
        assertThat(wallet.getBalanceMoney()).isEqualTo(Money.zeroInr());
        wallet.applyCredit(Money.inr("100.10"), NOW);
        wallet.applyCredit(Money.inr("0.20"), NOW);
        wallet.applyDebit(Money.inr("50.05"), NOW);
        assertThat(wallet.getBalanceMoney()).isEqualTo(Money.inr("50.25"));
    }

    @Test
    void overdraftIsRejected() {
        Wallet wallet = wallet();
        wallet.applyCredit(Money.inr("10"), NOW);
        assertThatThrownBy(() -> wallet.applyDebit(Money.inr("10.01"), NOW))
                .isInstanceOfSatisfying(PayverseException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(WalletErrorCode.WALLET_INSUFFICIENT_BALANCE);
                    assertThat(e.properties()).containsEntry("balance", "10.00").containsEntry("requested", "10.01");
                });
        assertThat(wallet.getBalanceMoney()).isEqualTo(Money.inr("10"));
    }

    @Test
    void freezeAndUnfreeze() {
        Wallet wallet = wallet();
        assertThat(wallet.freeze("fraud review", NOW)).isTrue();
        assertThat(wallet.freeze("again", NOW)).isFalse();
        assertThatThrownBy(wallet::ensureActive)
                .isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(WalletErrorCode.WALLET_FROZEN));
        assertThat(wallet.unfreeze(NOW)).isTrue();
        assertThat(wallet.unfreeze(NOW)).isFalse();
        wallet.ensureActive();
        assertThat(wallet.getStatusReason()).isNull();
    }

    @Test
    void tierOnlyMovesUp() {
        Wallet wallet = Wallet.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), KycTier.NONE, NOW);
        assertThat(wallet.changeTier(KycTier.FULL_KYC, NOW)).isTrue();
        assertThat(wallet.changeTier(KycTier.MIN_KYC, NOW)).isFalse();
        assertThat(wallet.getKycTier()).isEqualTo(KycTier.FULL_KYC);
    }

    @Test
    void ledgerPrimitives() {
        assertThatThrownBy(() -> LedgerEntry.of(UUID.randomUUID(), UUID.randomUUID(), EntrySide.DEBIT, Money.zeroInr(), null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        LedgerEntry entry = LedgerEntry.of(UUID.randomUUID(), UUID.randomUUID(), EntrySide.CREDIT, Money.inr("5"), Money.inr("15"), NOW);
        assertThat(entry.getAmountMoney()).isEqualTo(Money.inr("5"));
        assertThat(entry.getBalanceAfter()).isEqualByComparingTo("15");

        Journal journal = Journal.create(JournalType.ADD_MONEY, "k", "PgCharge", "ref", "x".repeat(300), null, NOW);
        assertThat(journal.getDescription()).hasSize(200);

        LimitUsage usage = LimitUsage.start(UUID.randomUUID(), LimitType.DAILY_DEBIT, LocalDate.of(2026, 10, 14));
        usage.add(Money.inr("99.99"));
        usage.add(Money.inr("0.01"));
        assertThat(usage.getUsedMoney()).isEqualTo(Money.inr("100"));

        LedgerAccount account = LedgerAccount.forWallet(UUID.randomUUID(), UUID.randomUUID(), NOW);
        assertThat(account.getCode()).startsWith("WALLET:");
        assertThat(account.getType()).isEqualTo(AccountType.LIABILITY);
        assertThat(SystemAccount.TRANSFER_SUSPENSE.id()).hasToString("00000000-0000-0000-0000-000000000003");
    }
}
