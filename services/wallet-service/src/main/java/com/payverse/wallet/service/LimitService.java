package com.payverse.wallet.service;

import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.wallet.config.WalletProperties;
import com.payverse.wallet.dto.LimitsResponse;
import com.payverse.wallet.entity.KycTier;
import com.payverse.wallet.entity.LimitType;
import com.payverse.wallet.entity.LimitUsage;
import com.payverse.wallet.entity.Wallet;
import com.payverse.wallet.exception.WalletErrorCode;
import com.payverse.wallet.repository.LimitUsageRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/** KYC-tier limits. Must be called with the wallet lock held (usage rows are read-modify-write). */
@Service
public class LimitService {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final LimitUsageRepository usage;
    private final WalletProperties properties;
    private final Clock clock;

    public LimitService(LimitUsageRepository usage, WalletProperties properties, Clock clock) {
        this.usage = usage;
        this.properties = properties;
        this.clock = clock;
    }

    /** Validates the posting against the wallet's tier and records usage; throws when a limit would be exceeded. */
    public void apply(Wallet wallet, Money amount, LimitPolicy policy) {
        if (policy == LimitPolicy.NONE) {
            return;
        }
        if (wallet.getKycTier() == KycTier.NONE) {
            throw new PayverseException(WalletErrorCode.WALLET_KYC_REQUIRED, "Complete minimum KYC to add, send or receive money");
        }
        WalletProperties.TierLimits limits = properties.limitsFor(wallet.getKycTier());
        switch (policy) {
            case DEBIT -> consume(wallet, LimitType.DAILY_DEBIT, today(), amount, Money.inr(limits.dailyDebit()));
            case CREDIT -> checkMaxBalance(wallet, amount, limits);
            case LOAD -> {
                checkMaxBalance(wallet, amount, limits);
                consume(wallet, LimitType.MONTHLY_LOAD, today().withDayOfMonth(1), amount, Money.inr(limits.monthlyLoad()));
            }
            case NONE -> { }
        }
    }

    /** Same checks as {@link #apply} without recording usage (used before charging a payment gateway). */
    public void check(Wallet wallet, Money amount, LimitPolicy policy) {
        if (policy == LimitPolicy.NONE) {
            return;
        }
        if (wallet.getKycTier() == KycTier.NONE) {
            throw new PayverseException(WalletErrorCode.WALLET_KYC_REQUIRED, "Complete minimum KYC to add, send or receive money");
        }
        WalletProperties.TierLimits limits = properties.limitsFor(wallet.getKycTier());
        if (policy == LimitPolicy.CREDIT || policy == LimitPolicy.LOAD) {
            checkMaxBalance(wallet, amount, limits);
        }
        if (policy == LimitPolicy.LOAD) {
            ensureWithin(LimitType.MONTHLY_LOAD, used(wallet, LimitType.MONTHLY_LOAD, today().withDayOfMonth(1)), amount,
                    Money.inr(limits.monthlyLoad()));
        }
        if (policy == LimitPolicy.DEBIT) {
            ensureWithin(LimitType.DAILY_DEBIT, used(wallet, LimitType.DAILY_DEBIT, today()), amount, Money.inr(limits.dailyDebit()));
        }
    }

    public LimitsResponse view(Wallet wallet) {
        WalletProperties.TierLimits limits = properties.limitsFor(wallet.getKycTier());
        return new LimitsResponse(
                Money.inr(limits.maxBalance()).toPlainString(),
                Money.inr(limits.monthlyLoad()).toPlainString(),
                used(wallet, LimitType.MONTHLY_LOAD, today().withDayOfMonth(1)).toPlainString(),
                Money.inr(limits.dailyDebit()).toPlainString(),
                used(wallet, LimitType.DAILY_DEBIT, today()).toPlainString());
    }

    private void checkMaxBalance(Wallet wallet, Money amount, WalletProperties.TierLimits limits) {
        Money max = Money.inr(limits.maxBalance());
        Money after = wallet.getBalanceMoney().plus(amount);
        if (after.isGreaterThan(max)) {
            throw exceeded("MAX_BALANCE", max,
                    max.minus(wallet.getBalanceMoney()).max(Money.zeroInr()));
        }
    }

    private void consume(Wallet wallet, LimitType type, LocalDate period, Money amount, Money limit) {
        LimitUsage row = usage.findByWalletIdAndLimitTypeAndPeriodStart(wallet.getId(), type, period)
                .orElseGet(() -> LimitUsage.start(wallet.getId(), type, period));
        ensureWithin(type, row.getUsedMoney(), amount, limit);
        row.add(amount);
        usage.save(row);
    }

    private static void ensureWithin(LimitType type, Money used, Money amount, Money limit) {
        if (used.plus(amount).isGreaterThan(limit)) {
            throw exceeded(type.name(), limit, limit.minus(used).max(Money.zeroInr()));
        }
    }

    private Money used(Wallet wallet, LimitType type, LocalDate period) {
        return usage.findByWalletIdAndLimitTypeAndPeriodStart(wallet.getId(), type, period)
                .map(LimitUsage::getUsedMoney).orElse(Money.zeroInr());
    }

    private static PayverseException exceeded(String limit, Money limitAmount, Money available) {
        return new PayverseException(WalletErrorCode.WALLET_LIMIT_EXCEEDED, limit + " limit exceeded",
                Map.of("limit", limit, "limitAmount", limitAmount.toPlainString(), "available", available.toPlainString()));
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), INDIA);
    }
}
