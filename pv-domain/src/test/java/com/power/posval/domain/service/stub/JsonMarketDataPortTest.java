package com.power.posval.domain.service.stub;

import com.power.posval.domain.port.marketdata.MarketDataLookup;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.*;

class JsonMarketDataPortTest {

    private static JsonMarketDataPort port;

    @BeforeAll
    static void setUp() {
        port = new JsonMarketDataPort();
    }

    @Test
    void lookupFixingReturnsCorrectValue() {
        MarketDataLookup result = port.lookupFixing(
            "EPEX_DA15", Instant.parse("2025-03-01T00:00:00Z"));
        assertEquals(new BigDecimal("25.62"), result.value());
        assertEquals("EPEX_DA15", result.series());
    }

    @Test
    void lookupFixingReturnZeroForMissingSeries() {
        MarketDataLookup result = port.lookupFixing(
            "NONEXISTENT", Instant.parse("2025-03-01T00:00:00Z"));
        assertEquals(BigDecimal.ZERO, result.value());
    }

    @Test
    void lookupFixingReturnZeroForMissingTimestamp() {
        MarketDataLookup result = port.lookupFixing(
            "EPEX_DA15", Instant.parse("2099-01-01T00:00:00Z"));
        assertEquals(BigDecimal.ZERO, result.value());
    }

    @Test
    void lookupForwardCurveReturnsCorrectPillar() {
        MarketDataLookup result = port.lookupForwardCurve(
            "EEX_BASE_DE",
            YearMonth.of(2025, 3),
            Instant.parse("2025-02-28T18:00:00Z"));
        assertEquals(new BigDecimal("74.2"), result.value());
    }

    @Test
    void lookupFxRateReturnsCorrectRate() {
        MarketDataLookup result = port.lookupFxRate(
            "EUR/USD", Instant.parse("2025-03-01T00:00:00Z"));
        assertEquals(new BigDecimal("1.0842"), result.value());
    }

    @Test
    void lookupSettlementSeries() {
        MarketDataLookup result = port.lookupFixing(
            "EPEX_DA15_SETTLE", Instant.parse("2025-03-01T00:00:00Z"));
        assertEquals(new BigDecimal("24.86"), result.value());
    }

    @Test
    void lookupNordpoolSeries() {
        MarketDataLookup result = port.lookupFixing(
            "NORDPOOL_SYS", Instant.parse("2025-03-01T00:00:00Z"));
        assertEquals(new BigDecimal("322.29"), result.value());
    }

    @Test
    void lookupForwardCurve24MonthHorizon() {
        // Verify 2-year forward curve is loaded
        MarketDataLookup q1_2027 = port.lookupForwardCurve(
            "EEX_BASE_DE", YearMonth.of(2027, 1),
            Instant.parse("2025-02-28T18:00:00Z"));
        assertEquals(new BigDecimal("93.1"), q1_2027.value());

        MarketDataLookup summer_2026 = port.lookupForwardCurve(
            "EEX_BASE_DE", YearMonth.of(2026, 7),
            Instant.parse("2025-02-28T18:00:00Z"));
        assertEquals(new BigDecimal("56.9"), summer_2026.value());
    }

    @Test
    void lookupEurNokFxRate() {
        MarketDataLookup result = port.lookupFxRate(
            "EUR/NOK", Instant.parse("2025-03-01T00:00:00Z"));
        assertEquals(new BigDecimal("0.0872"), result.value());
    }
}
