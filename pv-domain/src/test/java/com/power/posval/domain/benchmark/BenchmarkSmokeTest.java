package com.power.posval.domain.benchmark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test that verifies benchmark classes instantiate and run without errors.
 * Does NOT run full JMH (that's via: java -jar benchmarks.jar).
 * This ensures benchmark code compiles and executes correctly.
 */
class BenchmarkSmokeTest {

    @Test
    void priceExpressionBenchmark_executes() {
        var bench = new PriceExpressionBenchmark();
        bench.setup();

        var result = bench.evaluateCollarPpa();
        assertNotNull(result);
        assertNotNull(result.value());
    }

    @Test
    void domainBenchmarkSuite_qualityState() {
        var bench = new DomainBenchmarkSuite();
        bench.setup();

        assertTrue(bench.qualityStateTransition());
    }

    @Test
    void domainBenchmarkSuite_monthBlocks() {
        var bench = new DomainBenchmarkSuite();
        bench.setup();

        var blocks = bench.deliveryPeriodToMonthBlocks();
        assertEquals(12, blocks.size());
    }

    @Test
    void domainBenchmarkSuite_positionBuilder() {
        var bench = new DomainBenchmarkSuite();
        bench.setup();

        var entry = bench.positionLedgerEntryBuilder();
        assertNotNull(entry);
        assertEquals("T-7788", entry.tradeId());
    }

    @Test
    void domainBenchmarkSuite_volumeUnitEnergy() {
        var bench = new DomainBenchmarkSuite();
        bench.setup();

        var energy = bench.volumeUnitToEnergy();
        assertEquals(0, new java.math.BigDecimal("100.0").compareTo(
            energy.stripTrailingZeros()));
    }

    @Test
    void domainBenchmarkSuite_volumeSeriesBuilder() {
        var bench = new DomainBenchmarkSuite();
        bench.setup();

        var series = bench.volumeSeriesBuilder();
        assertNotNull(series);
    }

    @Test
    void domainBenchmarkSuite_numericPrecision() {
        var bench = new DomainBenchmarkSuite();
        bench.setup();

        var rounded = bench.numericPrecisionRound();
        assertNotNull(rounded);
        assertTrue(rounded.scale() <= 8);
    }

    @Test
    void fiveYearSettlementBenchmark_executes() {
        var bench = new FiveYearSettlementBenchmark();
        bench.setup();

        int cellCount = bench.materializeFiveYearTrade(null);

        // 5 years of 15-min intervals ≈ 175,200 cells
        assertTrue(cellCount > 170_000,
            "5-year trade should produce >170k cells, got " + cellCount);
        assertTrue(cellCount < 180_000,
            "5-year trade should produce <180k cells, got " + cellCount);
    }

    @Test
    void fiveYearSettlementBenchmark_singleMonth() {
        var bench = new FiveYearSettlementBenchmark();
        bench.setup();

        int cellCount = bench.materializeSingleMonth(null);

        // January has 31 days × 96 intervals = 2,976
        assertTrue(cellCount > 2900,
            "Single month should produce >2900 cells, got " + cellCount);
        assertTrue(cellCount < 3100,
            "Single month should produce <3100 cells, got " + cellCount);
    }
}
