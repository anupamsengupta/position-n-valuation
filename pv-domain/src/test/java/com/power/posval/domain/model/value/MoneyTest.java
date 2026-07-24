package com.power.posval.domain.model.value;

import com.power.posval.domain.port.DefaultNumericPrecision;
import com.power.posval.domain.port.NumericPrecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {

    private static final NumericPrecision NP = new DefaultNumericPrecision();
    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency USD = Currency.getInstance("USD");

    @Test
    void ofRoundsToMonetaryScale() {
        Money m = Money.of(new BigDecimal("12.123456789"), EUR, NP);
        assertEquals(new BigDecimal("12.1235"), m.amount());
        assertEquals(EUR, m.currency());
    }

    @Test
    void eurFactory() {
        Money m = Money.eur(new BigDecimal("100.999999"), NP);
        assertEquals(new BigDecimal("101.0000"), m.amount());
        assertEquals(EUR, m.currency());
    }

    @Test
    void addSameCurrency() {
        Money a = new Money(new BigDecimal("10.0000"), EUR);
        Money b = new Money(new BigDecimal("5.5000"), EUR);
        Money result = a.add(b);
        assertEquals(new BigDecimal("15.5000"), result.amount());
        assertEquals(EUR, result.currency());
    }

    @Test
    void addDifferentCurrencyThrows() {
        Money eur = new Money(new BigDecimal("10"), EUR);
        Money usd = new Money(new BigDecimal("10"), USD);
        assertThrows(IllegalArgumentException.class, () -> eur.add(usd));
    }

    @Test
    void multiplyRoundsToMonetaryScale() {
        Money m = new Money(new BigDecimal("100.0000"), EUR);
        Money result = m.multiply(new BigDecimal("0.333333"), NP);
        assertEquals(new BigDecimal("33.3333"), result.amount());
    }

    @Test
    void rejectsNullAmount() {
        assertThrows(NullPointerException.class, () -> new Money(null, EUR));
    }

    @Test
    void rejectsNullCurrency() {
        assertThrows(NullPointerException.class, () -> new Money(BigDecimal.TEN, null));
    }
}
