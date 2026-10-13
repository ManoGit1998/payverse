package com.payverse.wallet.service;

import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.wallet.config.WalletProperties;
import com.payverse.wallet.entity.KycTier;
import com.payverse.wallet.entity.LimitType;
import com.payverse.wallet.entity.LimitUsage;
import com.payverse.wallet.entity.Wallet;
import com.payverse.wallet.exception.WalletErrorCode;
import com.payverse.wallet.repository.LimitUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LimitServiceTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T20:00:00Z"), ZoneOffset.UTC); // 15 Oct 01:30 IST

    final Map<String, LimitUsage> store = new HashMap<>();
    final LimitUsageRepository repository = mock(LimitUsageRepository.class);
    final WalletProperties properties = new WalletProperties(null, null, null, null, null, null, null);
    LimitService service;

    @BeforeEach
    void setUp() {
        when(repository.findByWalletIdAndLimitTypeAndPeriodStart(any(), any(), any())).thenAnswer(inv ->
                Optional.ofNullable(store.get(inv.getArgument(1).toString() + inv.getArgument(2))));
        when(repository.save(any())).thenAnswer(inv -> {
            LimitUsage usage = inv.getArgument(0);
            store.put(usage.getLimitType().toString() + usage.getPeriodStart(), usage);
            return usage;
        });
        service = new LimitService(repository, properties, CLOCK);
    }

    Wallet wallet(KycTier tier, String balance) {
        Wallet wallet = Wallet.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), tier, CLOCK.instant());
        wallet.applyCredit(Money.inr(balance), CLOCK.instant());
        return wallet;
    }

    static void assertLimit(Runnable call, String limit) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(PayverseException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(WalletErrorCode.WALLET_LIMIT_EXCEEDED);
            assertThat(e.properties()).containsEntry("limit", limit);
        });
    }

    @Test
    void noKycMeansNoMoneyMovement() {
        Wallet wallet = wallet(KycTier.NONE, "0");
        for (LimitPolicy policy : new LimitPolicy[]{LimitPolicy.DEBIT, LimitPolicy.CREDIT, LimitPolicy.LOAD}) {
            assertThatThrownBy(() -> service.apply(wallet, Money.inr("1"), policy))
                    .isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(WalletErrorCode.WALLET_KYC_REQUIRED));
        }
        service.apply(wallet, Money.inr("1"), LimitPolicy.NONE);
    }

    @Test
    void maxBalanceForMinKyc() {
        Wallet wallet = wallet(KycTier.MIN_KYC, "9500");
        service.apply(wallet, Money.inr("500"), LimitPolicy.CREDIT);
        assertLimit(() -> service.apply(wallet, Money.inr("500.01"), LimitPolicy.CREDIT), "MAX_BALANCE");
    }

    @Test
    void monthlyLoadIsTrackedInIstMonths() {
        Wallet wallet = wallet(KycTier.MIN_KYC, "0");
        service.apply(wallet, Money.inr("6000"), LimitPolicy.LOAD);
        assertThat(store).containsKey(LimitType.MONTHLY_LOAD + LocalDate.of(2026, 10, 1).toString());
        assertLimit(() -> service.apply(wallet, Money.inr("4000.01"), LimitPolicy.LOAD), "MONTHLY_LOAD");
        service.check(wallet, Money.inr("4000"), LimitPolicy.LOAD);
    }

    @Test
    void dailyDebitUsesTheIndianCalendarDay() {
        Wallet wallet = wallet(KycTier.FULL_KYC, "150000");
        service.apply(wallet, Money.inr("99999"), LimitPolicy.DEBIT);
        assertThat(store).containsKey(LimitType.DAILY_DEBIT + "2026-10-15");
        assertLimit(() -> service.apply(wallet, Money.inr("1.01"), LimitPolicy.DEBIT), "DAILY_DEBIT");
        assertLimit(() -> service.check(wallet, Money.inr("1.01"), LimitPolicy.DEBIT), "DAILY_DEBIT");
    }

    @Test
    void viewShowsLimitsAndUsage() {
        Wallet wallet = wallet(KycTier.FULL_KYC, "1000");
        service.apply(wallet, Money.inr("250"), LimitPolicy.DEBIT);
        var view = service.view(wallet);
        assertThat(view.maxBalance()).isEqualTo("200000.00");
        assertThat(view.dailyDebitLimit()).isEqualTo("100000.00");
        assertThat(view.dailyDebitUsed()).isEqualTo("250.00");
        assertThat(view.monthlyLoadUsed()).isEqualTo("0.00");
    }

    @Test
    void mockGatewaysFollowTheirDocumentedRules() {
        PaymentGateway pg = new MockPaymentGatewayAdapter();
        assertThat(pg.charge(UUID.randomUUID(), Money.inr("100.13"), "UPI", "k")).isInstanceOf(PaymentGateway.ChargeResult.Declined.class);
        assertThat(pg.charge(UUID.randomUUID(), Money.inr("100.00"), "UPI", "k")).isInstanceOf(PaymentGateway.ChargeResult.Succeeded.class);
        PayoutGateway payout = new MockPayoutAdapter();
        assertThat(payout.initiate(UUID.randomUUID(), UUID.randomUUID(), Money.inr("10"), "k")).startsWith("MOCKIMPS-");
        assertThatThrownBy(() -> payout.initiate(UUID.randomUUID(), UUID.randomUUID(), Money.inr("10.14"), "k"))
                .isInstanceOfSatisfying(PayverseException.class, e -> assertThat(e.errorCode()).isEqualTo(WalletErrorCode.WALLET_PAYOUT_FAILED));
    }
}
