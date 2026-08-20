package com.power.posval.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the {@link TradeDirection} enum's sign() contract.
 * sign() is the single point of truth for direction-to-arithmetic mapping
 * used in DefaultTradeCaptureHandler. Settlement/revaluation paths use
 * quantity().signum() instead (OQ-1 in the tech spec).
 */
class TradeDirectionTest {

    @Test
    void buySignIsPositive() {
        assertEquals(1, TradeDirection.BUY.sign());
    }

    @Test
    void sellSignIsNegative() {
        assertEquals(-1, TradeDirection.SELL.sign());
    }

    @Test
    void valuesContainsBothDirections() {
        assertEquals(2, TradeDirection.values().length);
        assertNotNull(TradeDirection.valueOf("BUY"));
        assertNotNull(TradeDirection.valueOf("SELL"));
    }

    @Test
    void valueOfInvalidThrows() {
        assertThrows(IllegalArgumentException.class, () -> TradeDirection.valueOf("HEDGE"));
    }
}
