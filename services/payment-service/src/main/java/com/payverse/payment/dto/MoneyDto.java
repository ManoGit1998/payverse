package com.payverse.payment.dto;

import com.payverse.common.money.Money;

/** Money on the wire: {@code {"amount":"100.00","currency":"INR"}}. */
public record MoneyDto(String amount, String currency) {

    public static MoneyDto of(Money money) {
        return money == null ? null : new MoneyDto(money.toPlainString(), money.currency().getCurrencyCode());
    }
}
