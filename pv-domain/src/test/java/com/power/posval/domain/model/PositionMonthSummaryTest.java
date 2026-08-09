package com.power.posval.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PositionMonthSummaryTest {

    @Test
    void recordFieldsRetainedCorrectly() {
        UUID posId = UUID.randomUUID();
        var summary = new PositionMonthSummary(
            posId, "TN_0042", "T-1", "LEG-1",
            YearMonth.of(2025, 3),
            new BigDecimal("2880.00"),   // totalMwh
            new BigDecimal("50.00"),     // avgMw
            new BigDecimal("244800.00"), // totalAmount (85 * 2880)
            new BigDecimal("80640.00"),  // totalMarketAmount (28 * 2880)
            new BigDecimal("-164160.00"),// totalPnl
            new BigDecimal("85.00"),     // avgPrice
            new BigDecimal("28.00"),     // avgMarketPrice
            "EUR", 2976);               // cellCount (31 days * 96 QH)

        assertEquals(posId, summary.positionId());
        assertEquals("TN_0042", summary.tenantId());
        assertEquals(YearMonth.of(2025, 3), summary.deliveryMonth());
        assertEquals(0, new BigDecimal("2880.00").compareTo(summary.totalMwh()));
        assertEquals(0, new BigDecimal("50.00").compareTo(summary.avgMw()));
        assertEquals(0, new BigDecimal("244800.00").compareTo(summary.totalAmount()));
        assertEquals(0, new BigDecimal("80640.00").compareTo(summary.totalMarketAmount()));
        assertTrue(summary.totalPnl().compareTo(BigDecimal.ZERO) < 0, "PnL should be negative");
        assertEquals(2976, summary.cellCount());
    }

    @Test
    void nullableFieldsAllowed() {
        var summary = new PositionMonthSummary(
            UUID.randomUUID(), "TN_0042", "T-1", "LEG-1",
            YearMonth.of(2025, 3),
            new BigDecimal("100.00"),
            new BigDecimal("50.00"),
            new BigDecimal("8500.00"),
            null,  // no market amount
            null,  // no pnl
            new BigDecimal("85.00"),
            null,  // no market price
            "EUR", 96);

        assertNull(summary.totalMarketAmount());
        assertNull(summary.totalPnl());
        assertNull(summary.avgMarketPrice());
    }
}
