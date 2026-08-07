package com.power.posval.app.pricing;

import com.power.posval.domain.port.DefaultNumericPrecision;
import com.power.posval.domain.port.NumericPrecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MvelPrecisionHelper} — verifies each method applies
 * the correct NumericPrecision domain, matching the tree-walker's behavior.
 */
class MvelPrecisionHelperTest {

    private static final NumericPrecision NP = new DefaultNumericPrecision();
    private MvelPrecisionHelper helper;

    @BeforeEach
    void setUp() {
        helper = new MvelPrecisionHelper(NP);
    }

    // --- divide: INTERMEDIATE scale 10 ---

    @Test
    void divideAppliesIntermediateScale() {
        // 100 / 3 = 33.3333333333 (scale 10)
        BigDecimal result = helper.divide(new BigDecimal("100"), new BigDecimal("3"));
        assertEquals(0, new BigDecimal("33.3333333333").compareTo(result));
        assertEquals(10, result.scale());
    }

    @Test
    void divideNonTerminatingDoesNotThrow() {
        // 1 / 7 would throw ArithmeticException without scale
        BigDecimal result = helper.divide(BigDecimal.ONE, new BigDecimal("7"));
        assertEquals(10, result.scale());
    }

    // --- escalate: PRICE scale 8 ---

    @Test
    void escalateMultipliesAndRoundsToPrice() {
        // 72 * 1.0331186753 = 74.38454462... rounded to scale 8
        BigDecimal result = helper.escalate(
            new BigDecimal("72"), new BigDecimal("1.0331186753"));
        assertEquals(0, new BigDecimal("74.38454462").compareTo(result));
        assertEquals(8, result.scale());
    }

    // --- multiply: INTERMEDIATE scale 10 ---

    @Test
    void multiplyRoundsToIntermediate() {
        BigDecimal result = helper.multiply(
            new BigDecimal("10"), new BigDecimal("8.5"));
        assertEquals(0, new BigDecimal("85.0000000000").compareTo(result));
        assertEquals(10, result.scale());
    }

    // --- monetary: MONETARY scale 4 ---

    @Test
    void monetaryRoundsToScale4() {
        // 100 * 1.10 = 110.0000
        BigDecimal result = helper.monetary(
            new BigDecimal("100").multiply(new BigDecimal("1.10")));
        assertEquals(0, new BigDecimal("110.0000").compareTo(result));
        assertEquals(4, result.scale());
    }

    @Test
    void monetaryTruncatesExcessDigits() {
        // 123.456789 → 123.4568 (HALF_UP)
        BigDecimal result = helper.monetary(new BigDecimal("123.456789"));
        assertEquals(0, new BigDecimal("123.4568").compareTo(result));
    }

    // --- clamp ---

    @Test
    void clampInsideRange() {
        BigDecimal result = helper.clamp(
            new BigDecimal("38"), new BigDecimal("110"), new BigDecimal("74"));
        assertEquals(0, new BigDecimal("74").compareTo(result));
    }

    @Test
    void clampBelowFloor() {
        BigDecimal result = helper.clamp(
            new BigDecimal("38"), new BigDecimal("110"), new BigDecimal("20"));
        assertEquals(0, new BigDecimal("38").compareTo(result));
    }

    @Test
    void clampAboveCap() {
        BigDecimal result = helper.clamp(
            new BigDecimal("38"), new BigDecimal("110"), new BigDecimal("200"));
        assertEquals(0, new BigDecimal("110").compareTo(result));
    }

    // --- price: PRICE scale 8 ---

    @Test
    void priceRoundsToScale8() {
        BigDecimal result = helper.price(new BigDecimal("42.123456789012"));
        assertEquals(0, new BigDecimal("42.12345679").compareTo(result));
        assertEquals(8, result.scale());
    }

    // --- intermediate: INTERMEDIATE scale 10 ---

    @Test
    void intermediateRoundsToScale10() {
        BigDecimal result = helper.intermediate(new BigDecimal("42.12345678901234"));
        assertEquals(0, new BigDecimal("42.1234567890").compareTo(result));
        assertEquals(10, result.scale());
    }
}
