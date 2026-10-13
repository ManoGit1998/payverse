package com.payverse.common.money;

import java.util.Currency;

/** Thrown when arithmetic or comparison is attempted between different currencies. */
public class CurrencyMismatchException extends IllegalArgumentException {

    public CurrencyMismatchException(Currency left, Currency right) {
        super("Currency mismatch: " + left.getCurrencyCode() + " vs " + right.getCurrencyCode());
    }
}
