package com.ledgercore.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MoneyTest {
    @Test
    void formatsMinorUnits_withoutFloatingPoint() {
        assertThat(Money.format(125_050, "NGN")).isEqualTo("NGN 1,250.50");
        assertThat(Money.format(5, "USD")).isEqualTo("USD 0.05");
        assertThat(Money.format(100_000_000_000L, "NGN")).isEqualTo("NGN 1,000,000,000.00");
        assertThat(Money.format(0, "EUR")).isEqualTo("EUR 0.00");
    }

    @Test
    void knowsSupportedCurrencies() {
        assertThat(Money.isSupported("NGN")).isTrue();
        assertThat(Money.isSupported("ngn")).isFalse();
        assertThat(Money.isSupported("JPY")).isFalse();
        assertThat(Money.isSupported(null)).isFalse();
    }
}
