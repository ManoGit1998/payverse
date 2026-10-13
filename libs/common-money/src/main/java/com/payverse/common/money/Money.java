package com.payverse.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * Immutable monetary amount. Always scale 2, rounded HALF_EVEN, INR by default.
 * <p>
 * Never construct money from {@code double}/{@code float}: only {@link BigDecimal},
 * {@link String} or minor units ({@code long} paise) are accepted.
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;
    public static final Currency INR = Currency.getInstance("INR");

    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        amount = amount.setScale(SCALE, ROUNDING);
    }

    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    public static Money of(BigDecimal amount, String currencyCode) {
        return new Money(amount, Currency.getInstance(currencyCode));
    }

    public static Money inr(BigDecimal amount) {
        return new Money(amount, INR);
    }

    public static Money inr(String amount) {
        return new Money(new BigDecimal(amount), INR);
    }

    /** Creates money from minor units, e.g. {@code ofMinor(12345, INR)} is 123.45. */
    public static Money ofMinor(long minorUnits, Currency currency) {
        return new Money(BigDecimal.valueOf(minorUnits, SCALE), currency);
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public static Money zeroInr() {
        return zero(INR);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money multiply(BigDecimal factor) {
        Objects.requireNonNull(factor, "factor must not be null");
        return new Money(amount.multiply(factor), currency);
    }

    /** Percentage of this amount, e.g. {@code percentage(new BigDecimal("2.5"))}. */
    public Money percentage(BigDecimal percent) {
        Objects.requireNonNull(percent, "percent must not be null");
        return new Money(amount.multiply(percent).divide(BigDecimal.valueOf(100), SCALE, ROUNDING), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    public Money abs() {
        return new Money(amount.abs(), currency);
    }

    public Money min(Money other) {
        return compareTo(other) <= 0 ? this : other;
    }

    public Money max(Money other) {
        return compareTo(other) >= 0 ? this : other;
    }

    /**
     * Splits this amount into {@code parts} shares that sum exactly to this amount.
     * Leftover paise are distributed one each to the first shares.
     */
    public List<Money> allocate(int parts) {
        if (parts <= 0) {
            throw new IllegalArgumentException("parts must be positive");
        }
        long total = toMinorUnits();
        long base = total / parts;
        long remainder = total % parts;
        List<Money> shares = new ArrayList<>(parts);
        for (int i = 0; i < parts; i++) {
            long share = base + (i < Math.abs(remainder) ? Long.signum(remainder) : 0);
            shares.add(ofMinor(share, currency));
        }
        return List.copyOf(shares);
    }

    public long toMinorUnits() {
        return amount.movePointRight(SCALE).longValueExact();
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isGreaterThanOrEqual(Money other) {
        return compareTo(other) >= 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    /** Plain amount string, e.g. {@code "100.00"} — the wire format used in APIs and events. */
    public String toPlainString() {
        return amount.toPlainString();
    }

    @Override
    public String toString() {
        return currency.getCurrencyCode() + " " + amount.toPlainString();
    }
}
