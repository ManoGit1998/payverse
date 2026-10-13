package com.payverse.common.money;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    private static final Currency USD = Currency.getInstance("USD");

    @Nested
    class Construction {

        @Test
        void normalisesScaleToTwo() {
            assertThat(Money.inr("10").amount()).isEqualByComparingTo("10.00");
            assertThat(Money.inr("10").amount().scale()).isEqualTo(2);
        }

        @ParameterizedTest
        @CsvSource({"0.125,0.12", "0.135,0.14", "0.145,0.14", "-0.125,-0.12", "2.675,2.68"})
        void roundsHalfEven(String input, String expected) {
            assertThat(Money.inr(input).amount()).isEqualTo(new BigDecimal(expected));
        }

        @Test
        void defaultsToInr() {
            assertThat(Money.inr("1").currency()).isEqualTo(Money.INR);
            assertThat(Money.zeroInr().isZero()).isTrue();
        }

        @Test
        void fromMinorUnits() {
            assertThat(Money.ofMinor(12345, Money.INR)).isEqualTo(Money.inr("123.45"));
            assertThat(Money.inr("123.45").toMinorUnits()).isEqualTo(12345);
        }

        @Test
        void fromCurrencyCode() {
            assertThat(Money.of(BigDecimal.ONE, "USD").currency()).isEqualTo(USD);
            assertThat(Money.of(BigDecimal.ONE, USD)).isEqualTo(Money.of(new BigDecimal("1.00"), "USD"));
        }

        @Test
        void rejectsNulls() {
            assertThatThrownBy(() -> Money.of(null, Money.INR)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> Money.of(BigDecimal.ONE, (Currency) null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void equalityIgnoresInputScale() {
            assertThat(Money.inr("5")).isEqualTo(Money.inr("5.000"));
            assertThat(Money.inr("5")).hasSameHashCodeAs(Money.inr("5.00"));
        }
    }

    @Nested
    class Arithmetic {

        @Test
        void addsAndSubtracts() {
            assertThat(Money.inr("10.10").plus(Money.inr("0.20"))).isEqualTo(Money.inr("10.30"));
            assertThat(Money.inr("10.10").minus(Money.inr("20.20"))).isEqualTo(Money.inr("-10.10"));
        }

        @Test
        void multipliesWithRounding() {
            assertThat(Money.inr("10.00").multiply(new BigDecimal("0.333"))).isEqualTo(Money.inr("3.33"));
        }

        @Test
        void percentage() {
            assertThat(Money.inr("199.00").percentage(new BigDecimal("2.5"))).isEqualTo(Money.inr("4.98"));
        }

        @Test
        void negateAbsMinMax() {
            Money a = Money.inr("5");
            Money b = Money.inr("7");
            assertThat(a.negate()).isEqualTo(Money.inr("-5"));
            assertThat(a.negate().abs()).isEqualTo(a);
            assertThat(a.min(b)).isEqualTo(a);
            assertThat(a.max(b)).isEqualTo(b);
        }

        @Test
        void rejectsCurrencyMismatch() {
            assertThatThrownBy(() -> Money.inr("1").plus(Money.of(BigDecimal.ONE, USD)))
                    .isInstanceOf(CurrencyMismatchException.class)
                    .hasMessageContaining("INR vs USD");
            assertThatThrownBy(() -> Money.inr("1").compareTo(Money.of(BigDecimal.ONE, USD)))
                    .isInstanceOf(CurrencyMismatchException.class);
        }

        @Test
        void avoidsFloatingPointDrift() {
            Money sum = Money.zeroInr();
            for (int i = 0; i < 10; i++) {
                sum = sum.plus(Money.inr("0.10"));
            }
            assertThat(sum).isEqualTo(Money.inr("1.00"));
        }
    }

    @Nested
    class Comparison {

        @Test
        void signPredicates() {
            assertThat(Money.inr("1").isPositive()).isTrue();
            assertThat(Money.inr("-1").isNegative()).isTrue();
            assertThat(Money.inr("0").isZero()).isTrue();
        }

        @Test
        void ordering() {
            Money small = Money.inr("1");
            Money big = Money.inr("2");
            assertThat(big.isGreaterThan(small)).isTrue();
            assertThat(big.isGreaterThanOrEqual(big)).isTrue();
            assertThat(small.isLessThan(big)).isTrue();
            assertThat(small.compareTo(big)).isNegative();
        }
    }

    @Nested
    class Allocation {

        @Test
        void splitsExactlyWithRemainderToFirstShares() {
            List<Money> shares = Money.inr("100.00").allocate(3);
            assertThat(shares).containsExactly(Money.inr("33.34"), Money.inr("33.33"), Money.inr("33.33"));
        }

        @Test
        void negativeAmountsAllocateExactly() {
            List<Money> shares = Money.inr("-0.05").allocate(2);
            assertThat(shares).containsExactly(Money.inr("-0.03"), Money.inr("-0.02"));
        }

        @Test
        void rejectsNonPositiveParts() {
            assertThatThrownBy(() -> Money.inr("1").allocate(0)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void sharesAlwaysSumToTotal() {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < 1_000; i++) {
                Money total = Money.ofMinor(random.nextLong(0, 10_000_000), Money.INR);
                int parts = random.nextInt(1, 50);
                Money sum = total.allocate(parts).stream().reduce(Money.zeroInr(), Money::plus);
                assertThat(sum).isEqualTo(total);
            }
        }
    }

    @Test
    void formatting() {
        assertThat(Money.inr("1234.5")).hasToString("INR 1234.50");
        assertThat(Money.inr("1234.5").toPlainString()).isEqualTo("1234.50");
    }
}
