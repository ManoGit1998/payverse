package com.payverse.wallet.dto;

import com.payverse.common.money.Money;

/** Money on the wire: {@code {"amount":"100.00","currency":"INR"}} (string, never a float). */
public record MoneyDto(String amount, String currency) {

    public static MoneyDto of(Money money) {
        return new MoneyDto(money.toPlainString(), money.currency().getCurrencyCode());
    }
}
