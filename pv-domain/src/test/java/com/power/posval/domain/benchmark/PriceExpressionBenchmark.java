package com.power.posval.domain.benchmark;

import com.power.posval.domain.model.expression.*;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.port.DefaultNumericPrecision;
import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.marketdata.MarketDataPort;
import com.power.posval.domain.service.PriceExpressionBasedEvaluator;
import com.power.posval.domain.service.PriceEvaluator;
import com.power.posval.domain.service.PriceResolution;
import com.power.posval.domain.service.ResolutionPurpose;
import org.openjdk.jmh.annotations.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;

/**
 * JMH microbenchmark for PriceExpression evaluation. §18a.1, TR-043.
 * Target p95: < 50 µs for collar PPA tree.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
@State(Scope.Benchmark)
public class PriceExpressionBenchmark {

    private PriceExpression collarPpaExpression;
    private PriceEvaluator evaluator;
    private MarketDataPort stubMarketData;
    private DeliveryPeriod testPeriod;

    @Setup
    public void setup() {
        evaluator = new PriceExpressionBasedEvaluator(new DefaultNumericPrecision());

        var basePrice = new MarketDataLeaf("base-leaf", "EPEX-DE-LU-DA15",
            null, 0, "SINGLE_INTERVAL");
        var spread = new ConstantLeaf("spread-leaf", new BigDecimal("2.50"), "EUR/MWh");
        var baseWithSpread = new Add(basePrice, spread);

        var floor = new ConstantLeaf("floor-leaf", new BigDecimal("40.00"), "EUR/MWh");
        var cap = new ConstantLeaf("cap-leaf", new BigDecimal("120.00"), "EUR/MWh");
        collarPpaExpression = new Clamp(floor, cap, baseWithSpread);

        var tz = ZoneId.of("Europe/Berlin");
        testPeriod = new DeliveryPeriod(
            ZonedDateTime.of(2025, 8, 1, 0, 0, 0, 0, tz),
            ZonedDateTime.of(2025, 8, 1, 1, 0, 0, 0, tz), tz);

        stubMarketData = new StubMarketDataPort();
    }

    @Benchmark
    public PriceResolution evaluateCollarPpa() {
        return evaluator.evaluate(collarPpaExpression,
            testPeriod, ResolutionPurpose.FORWARD, stubMarketData);
    }

    private static class StubMarketDataPort implements MarketDataPort {
        @Override
        public MarketDataLookup lookupFixing(String series, Instant intervalStart) {
            return new MarketDataLookup(new BigDecimal("68.20"), 42L, series, intervalStart, null);
        }
        @Override
        public MarketDataLookup lookupIndex(String series, String refMonthExpr, DeliveryPeriod dp) {
            return new MarketDataLookup(new BigDecimal("68.20"), 42L, series, Instant.now(), null);
        }
        @Override
        public MarketDataLookup lookupForwardCurve(String series, YearMonth pillar, Instant asOf) {
            return new MarketDataLookup(new BigDecimal("72.00"), 43L, series, asOf, null);
        }
        @Override
        public MarketDataLookup lookupFxRate(String pair, Instant refDate) {
            return new MarketDataLookup(BigDecimal.ONE, 1L, pair, refDate, null);
        }
        @Override
        public MarketDataLookup lookupAtVersion(String series, Instant intervalStart, long versionId) {
            return lookupFixing(series, intervalStart);
        }
    }
}
