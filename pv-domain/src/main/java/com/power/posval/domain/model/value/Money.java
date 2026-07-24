package com.power.posval.domain.model.value;

import com.power.posval.domain.port.NumericPrecision;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * Monetary amount with currency. Scale governed by NumericPrecision.MONETARY (§5.0).
 * Arithmetic operations preserve currency; cross-currency arithmetic is a type error.
 * Pattern #3, #8, FR-036, D-2, S2/S5a.
 */
public record Money(BigDecimal amount, Currency currency) {
    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
    }

    /** Factory: rounds to MONETARY scale via NumericPrecision (TR-048). */
    public static Money of(BigDecimal amount, Currency currency, NumericPrecision np) {
        return new Money(np.round(amount, NumericPrecision.Domain.MONETARY), currency);
    }

    public static Money eur(BigDecimal amount, NumericPrecision np) {
        return of(amount, Currency.getInstance("EUR"), np);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    /** Multiply and round to MONETARY scale (TR-048). */
    public Money multiply(BigDecimal factor, NumericPrecision np) {
        return new Money(np.round(amount.multiply(factor), NumericPrecision.Domain.MONETARY), currency);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                "Cannot combine %s and %s".formatted(currency, other.currency));
        }
    }
}
