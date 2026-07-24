package com.power.posval.domain.port;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.*;

class DefaultNumericPrecisionTest {

    private final NumericPrecision np = new DefaultNumericPrecision();

    @Test
    void scaleMonetary() { assertEquals(4, np.scale(NumericPrecision.Domain.MONETARY)); }

    @Test
    void scalePrice() { assertEquals(8, np.scale(NumericPrecision.Domain.PRICE)); }

    @Test
    void scaleVolume() { assertEquals(8, np.scale(NumericPrecision.Domain.VOLUME)); }

    @Test
    void scaleEnergy() { assertEquals(8, np.scale(NumericPrecision.Domain.ENERGY)); }

    @Test
    void scaleMultiplier() { assertEquals(8, np.scale(NumericPrecision.Domain.MULTIPLIER)); }

    @Test
    void scaleIntermediate() { assertEquals(10, np.scale(NumericPrecision.Domain.INTERMEDIATE)); }

    @Test
    void precisionMonetary() { assertEquals(20, np.precision(NumericPrecision.Domain.MONETARY)); }

    @Test
    void precisionPrice() { assertEquals(20, np.precision(NumericPrecision.Domain.PRICE)); }

    @Test
    void precisionVolume() { assertEquals(18, np.precision(NumericPrecision.Domain.VOLUME)); }

    @Test
    void precisionEnergy() { assertEquals(20, np.precision(NumericPrecision.Domain.ENERGY)); }

    @Test
    void precisionMultiplier() { assertEquals(10, np.precision(NumericPrecision.Domain.MULTIPLIER)); }

    @Test
    void precisionIntermediate() { assertEquals(24, np.precision(NumericPrecision.Domain.INTERMEDIATE)); }

    @Test
    void defaultRoundingModeIsHalfUp() {
        assertEquals(RoundingMode.HALF_UP, np.roundingMode());
    }

    @Test
    void roundAppliesScaleAndRounding() {
        BigDecimal value = new BigDecimal("12.123456789");
        BigDecimal rounded = np.round(value, NumericPrecision.Domain.MONETARY);
        assertEquals(new BigDecimal("12.1235"), rounded);
    }

    @Test
    void roundToPrice() {
        BigDecimal value = new BigDecimal("85.123456789012");
        BigDecimal rounded = np.round(value, NumericPrecision.Domain.PRICE);
        assertEquals(new BigDecimal("85.12345679"), rounded);
    }

    @ParameterizedTest
    @EnumSource(NumericPrecision.Domain.class)
    void allDomainsHavePositiveScale(NumericPrecision.Domain domain) {
        assertTrue(np.scale(domain) > 0, "scale must be positive for " + domain);
    }

    @ParameterizedTest
    @EnumSource(NumericPrecision.Domain.class)
    void allDomainsHavePositivePrecision(NumericPrecision.Domain domain) {
        assertTrue(np.precision(domain) > 0, "precision must be positive for " + domain);
    }

    @ParameterizedTest
    @EnumSource(NumericPrecision.Domain.class)
    void precisionIsGreaterOrEqualToScale(NumericPrecision.Domain domain) {
        assertTrue(np.precision(domain) >= np.scale(domain),
            "precision must be >= scale for " + domain);
    }
}
