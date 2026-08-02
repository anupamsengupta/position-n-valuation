package com.power.posval.domain.event;

import com.power.posval.domain.port.marketdata.MarketDataType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarketDataEventSmokeTest {

    private static final Instant NOW = Instant.now();

    @Test
    void marketDataUpdatedFullSeriesInvalidation() {
        var event = new MarketDataUpdated(
            "tenant-1", MarketDataType.FIXING, "EPEX_DA15",
            null, null, 5L, NOW);
        assertEquals("tenant-1", event.tenantId());
        assertEquals(MarketDataType.FIXING, event.dataType());
        assertNull(event.affectedRangeStart());
        assertNull(event.affectedRangeEnd());
        assertEquals(5L, event.newVersionId());
    }

    @Test
    void marketDataUpdatedRangeInvalidation() {
        Instant start = Instant.parse("2025-03-01T00:00:00Z");
        Instant end = Instant.parse("2025-04-01T00:00:00Z");
        var event = new MarketDataUpdated(
            "tenant-1", MarketDataType.FORWARD_CURVE, "EEX_BASE",
            start, end, 10L, NOW);
        assertEquals(start, event.affectedRangeStart());
        assertEquals(end, event.affectedRangeEnd());
    }

    @Test
    void curveTickConstruction() {
        var pillars = List.of(YearMonth.of(2025, 3), YearMonth.of(2025, 4));
        var event = new CurveTick(
            "tenant-1", "EEX_BASE_DE", pillars,
            Instant.parse("2025-03-01T12:00:00Z"), 7L, NOW);
        assertEquals("tenant-1", event.tenantId());
        assertEquals("EEX_BASE_DE", event.series());
        assertEquals(2, event.affectedPillars().size());
        assertEquals(7L, event.versionId());
    }

    @Test
    void curveTickAllMarketDataTypes() {
        for (MarketDataType type : MarketDataType.values()) {
            var event = new MarketDataUpdated(
                "t", type, "s", null, null, 1L, NOW);
            assertEquals(type, event.dataType());
        }
    }
}
