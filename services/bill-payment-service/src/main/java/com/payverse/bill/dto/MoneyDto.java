package com.payverse.bill.dto;

import com.payverse.common.money.Money;

/** Wire format for money: {@code {"amount": "10.00", "currency": "INR"}}. */
public record MoneyDto(String amount, String currency) {

    public static MoneyDto of(Money money) {
        return money == null ? null : new MoneyDto(money.amount().toPlainString(), money.currency().getCurrencyCode());
    }
}
