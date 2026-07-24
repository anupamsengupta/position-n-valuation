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
}
