package com.payverse.common.events;

import com.payverse.common.money.Money;

import java.math.BigDecimal;

/** Wire representation of money inside events: {@code {"value":"100.00","currency":"INR"}}. */
public record Amount(BigDecimal value, String currency) {

    public static Amount of(Money money) {
        return new Amount(money.amount(), money.currency().getCurrencyCode());
    }

    public Money toMoney() {
        return Money.of(value, currency);
    }
}
