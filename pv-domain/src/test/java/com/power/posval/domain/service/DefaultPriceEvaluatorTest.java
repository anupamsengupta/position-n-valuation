package com.power.posval.domain.service;

import com.power.posval.domain.model.QualityState;
import com.power.posval.domain.model.expression.*;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.port.DefaultNumericPrecision;
import com.power.posval.domain.port.NumericPrecision;
import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.marketdata.MarketDataPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

class DefaultPriceEvaluatorTest {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");
    private static final NumericPrecision NP = new DefaultNumericPrecision();

    private DefaultPriceEvaluator evaluator;
    private DeliveryPeriod interval;

    @BeforeEach
    void setUp() {
        evaluator = new DefaultPriceEvaluator(NP);
        interval = new DeliveryPeriod(
            ZonedDateTime.of(2025, 3, 1, 0, 0, 0, 0, CET),
            ZonedDateTime.of(2025, 3, 1, 1, 0, 0, 0, CET),
            CET
        );
    }

    private MarketDataPort stubPort(BigDecimal fixingValue, long versionId) {
        return new MarketDataPort() {
            @Override
            public MarketDataLookup lookupFixing(String series, Instant intervalStart) {
                return new MarketDataLookup(fixingValue, versionId, series, intervalStart, null);
            }
            @Override
            public MarketDataLookup lookupIndex(String series, String refMonthExpr, DeliveryPeriod dp) {
                return new MarketDataLookup(fixingValue, versionId, series, dp.start().toInstant(), null);
            }
            @Override
            public MarketDataLookup lookupForwardCurve(String series, YearMonth pillar, Instant asOfDate) {
                return new MarketDataLookup(fixingValue, versionId, series, asOfDate, null);
            }
            @Override
            public MarketDataLookup lookupFxRate(String currencyPair, Instant referenceDate) {
                return new MarketDataLookup(fixingValue, versionId, currencyPair, referenceDate, null);
            }
            @Override
            public MarketDataLookup lookupAtVersion(String series, Instant intervalStart, long ver) {
                return new MarketDataLookup(fixingValue, ver, series, intervalStart, null);
            }
        };
    }

    // --- Fixed price ---

    @Test
    void fixedPrice() {
        var expr = new ConstantLeaf("fixed", new BigDecimal("85.50"), "EUR/MWh");
        PriceResolution result = evaluator.evaluate(expr, interval, ResolutionPurpose.FORWARD, stubPort(BigDecimal.ZERO, 1));

        assertEquals(0, new BigDecimal("85.50000000").compareTo(result.value()));
        assertTrue(result.activeLeaves().contains("fixed"));
        assertTrue(result.inputVersionSet().isEmpty());
    }

    // --- Add ---

    @Test
    void addTwoConstants() {
        var left = new ConstantLeaf("a", new BigDecimal("80"), "EUR/MWh");
        var right = new ConstantLeaf("b", new BigDecimal("5.50"), "EUR/MWh");
        var expr = new Add(left, right);

        PriceResolution result = evaluator.evaluate(expr, interval, ResolutionPurpose.FORWARD, stubPort(BigDecimal.ZERO, 1));
        assertEquals(0, new BigDecimal("85.50000000").compareTo(result.value()));
        assertEquals(2, result.activeLeaves().size());
    }

    // --- Subtract ---

    @Test
    void subtractConstants() {
        var left = new ConstantLeaf("a", new BigDecimal("100"), null);
        var right = new ConstantLeaf("b", new BigDecimal("15"), null);
        var expr = new Subtract(left, right);

        PriceResolution result = evaluator.evaluate(expr, interval, ResolutionPurpose.FORWARD, stubPort(BigDecimal.ZERO, 1));
        assertEquals(0, new BigDecimal("85.00000000").compareTo(result.value()));
    }

    // --- Multiply ---

    @Test
    void multiplyConstants() {
        var left = new ConstantLeaf("a", new BigDecimal("10"), null);
        var right = new ConstantLeaf("b", new BigDecimal("8.5"), null);
        var expr = new Multiply(left, right);

        PriceResolution result = evaluator.evaluate(expr, interval, ResolutionPurpose.FORWARD, stubPort(BigDecimal.ZERO, 1));
        assertEquals(0, new BigDecimal("85.0000000000").compareTo(result.value())); // INTERMEDIATE scale=10
    }

    // --- Divide ---

    @Test
    void divideConstants() {
        var num = new ConstantLeaf("n", new BigDecimal("100"), null);
        var den = new ConstantLeaf("d", new BigDecimal("3"), null);
        var expr = new Divide(num, den);

        PriceResolution result = evaluator.evaluate(expr, interval, ResolutionPurpose.FORWARD, stubPort(BigDecimal.ZERO, 1));
        // 100/3 = 33.3333333333 at INTERMEDIATE scale 10, then rounded to PRICE scale 8 in final result
        assertEquals(0, new BigDecimal("33.33333333").compareTo(result.value()));
    }

    // --- Clamp: inside collar ---

    @Test
    void clampInsideCollar() {
        var min = new ConstantLeaf("min", new BigDecimal("50"), null);
        var max = new ConstantLeaf("max", new BigDecimal("100"), null);
        var inner = new ConstantLeaf("val", new BigDecimal("75"), null);
        var expr = new Clamp(min, max, inner);

        PriceResolution result = evaluator.evaluate(expr, interval, ResolutionPurpose.FORWARD, stubPort(BigDecimal.ZERO, 1));
        assertEquals(0, new BigDecimal("75").compareTo(result.value()));
        // Bound leaves should NOT be active when value is inside collar
        assertTrue(result.activeLeaves().contains("val"));
        assertFalse(result.activeLeaves().contains("min"));
        assertFalse(result.activeLeaves().contains("max"));
    }

    // --- Clamp: binding ---

    @Test
    void clampBindingFloor() {
        var min = new ConstantLeaf("min", new BigDecimal("50"), null);
        var max = new ConstantLeaf("max", new BigDecimal("100"), null);
        var inner = new ConstantLeaf("val", new BigDecimal("30"), null);
        var expr = new Clamp(min, max, inner);

        PriceResolution result = evaluator.evaluate(expr, interval, ResolutionPurpose.FORWARD, stubPort(BigDecimal.ZERO, 1));
        assertEquals(0, new BigDecimal("50").compareTo(result.value()));
        // Bound leaves ARE active when clamped
        assertTrue(result.activeLeaves().contains("min"));
        assertTrue(result.activeLeaves().contains("max"));
    }

    @Test
    void clampBindingCeiling() {
        var min = new ConstantLeaf("min", new BigDecimal("50"), null);
        var max = new ConstantLeaf("max", new BigDecimal("100"), null);
        var inner = new ConstantLeaf("val", new BigDecimal("150"), null);
        var expr = new Clamp(min, max, inner);

        PriceResolution result = evaluator.evaluate(expr, interval, ResolutionPurpose.FORWARD, stubPort(BigDecimal.ZERO, 1));
        assertEquals(0, new BigDecimal("100").compareTo(result.value()));
        assertTrue(result.activeLeaves().contains("min"));
        assertTrue(result.activeLeaves().contains("max"));
    }

    // --- ConditionalGate ---

    @Test
    void conditionalGateFires() {
        var gateInput = new ConstantLeaf("gate", new BigDecimal("-5"), null);
        var override = new ConstantLeaf("override", BigDecimal.ZERO, null);
        var inner = new ConstantLeaf("inner", new BigDecimal("85"), null);
        var expr = new ConditionalGate(gateInput, "< 0", override, inner);

        PriceResolution result = evaluator.evaluate(expr, interval, ResolutionPurpose.FORWARD, stubPort(BigDecimal.ZERO, 1));
        assertEquals(0, BigDecimal.ZERO.compareTo(result.value()));
    }

    @Test
    void conditionalGateDoesNotFire() {
        var gateInput = new ConstantLeaf("gate", new BigDecimal("5"), null);
        var override = new ConstantLeaf("override", BigDecimal.ZERO, null);
        var inner = new ConstantLeaf("inner", new BigDecimal("85"), null);
        var expr = new ConditionalGate(gateInput, "< 0", override, inner);

        PriceResolution result = evaluator.evaluate(expr, interval, ResolutionPurpose.FORWARD, stubPort(BigDecimal.ZERO, 1));
        assertEquals(0, new BigDecimal("85").compareTo(result.value()));
    }

    // --- FxConvert ---

    @Test
    void fxConvert() {
        var value = new ConstantLeaf("val", new BigDecimal("100"), "EUR/MWh");
        var rate = new ConstantLeaf("rate", new BigDecimal("1.10"), "EUR/USD");
        var expr = new FxConvert(value, rate);

        PriceResolution result = evaluator.evaluate(expr, interval, ResolutionPurpose.FORWARD, stubPort(BigDecimal.ZERO, 1));
        // 100 * 1.10 = 110.0000 (MONETARY scale 4)
        assertEquals(0, new BigDecimal("110.0000").compareTo(result.value()));
    }

    // --- MarketDataLeaf ---

    @Test
    void marketDataLeafForward() {
        var leaf = new MarketDataLeaf("spot", "EPEX_SPOT", "EPEX_SETTLEMENT", 0, null);
        PriceResolution result = evaluator.evaluate(leaf, interval, ResolutionPurpose.FORWARD,
            stubPort(new BigDecimal("42.50"), 7L));

        assertEquals(0, new BigDecimal("42.50000000").compareTo(result.value()));
        assertTrue(result.activeLeaves().contains("spot"));
        assertEquals(7L, result.inputVersionSet().get("EPEX_SPOT"));
    }

    @Test
    void marketDataLeafSettlementUsesSettlementSeries() {
        var leaf = new MarketDataLeaf("spot", "EPEX_SPOT", "EPEX_SETTLEMENT", 0, null);
        MarketDataPort port = new MarketDataPort() {
            @Override
            public MarketDataLookup lookupFixing(String series, Instant intervalStart) {
                // Verify settlement series is used
                return new MarketDataLookup(
                    "EPEX_SETTLEMENT".equals(series) ? new BigDecimal("99") : new BigDecimal("50"),
                    10L, series, intervalStart, null);
            }
            @Override
            public MarketDataLookup lookupIndex(String s, String r, DeliveryPeriod dp) { return null; }
            @Override
            public MarketDataLookup lookupForwardCurve(String s, YearMonth p, Instant a) { return null; }
            @Override
            public MarketDataLookup lookupFxRate(String c, Instant r) { return null; }
            @Override
            public MarketDataLookup lookupAtVersion(String s, Instant i, long v) { return null; }
        };

        PriceResolution result = evaluator.evaluate(leaf, interval, ResolutionPurpose.SETTLEMENT, port);
        assertEquals(0, new BigDecimal("99").compareTo(result.value()));
    }

    // --- Escalate ---

    @Test
    void escalateMultipliesAndRounds() {
        var base = new ConstantLeaf("base", new BigDecimal("100"), null);
        var ratio = new ConstantLeaf("ratio", new BigDecimal("1.05"), null);
        var expr = new Escalate(base, ratio);

        PriceResolution result = evaluator.evaluate(expr, interval, ResolutionPurpose.FORWARD, stubPort(BigDecimal.ZERO, 1));
        assertEquals(0, new BigDecimal("105.00000000").compareTo(result.value()));
    }

    // --- Nested PPA-like tree ---

    @Test
    void nestedPpaTree() {
        // PPA price = clamp(min=50, max=100, base_price + premium)
        var basePrice = new ConstantLeaf("base", new BigDecimal("80"), "EUR/MWh");
        var premium = new ConstantLeaf("prem", new BigDecimal("10"), "EUR/MWh");
        var sum = new Add(basePrice, premium);
        var min = new ConstantLeaf("floor", new BigDecimal("50"), null);
        var max = new ConstantLeaf("cap", new BigDecimal("100"), null);
        var expr = new Clamp(min, max, sum);

        PriceResolution result = evaluator.evaluate(expr, interval, ResolutionPurpose.FORWARD, stubPort(BigDecimal.ZERO, 1));
        // 80 + 10 = 90, within [50, 100], so result = 90
        assertEquals(0, new BigDecimal("90").compareTo(result.value()));
        assertTrue(result.activeLeaves().contains("base"));
        assertTrue(result.activeLeaves().contains("prem"));
        // Bounds not active because 90 is within collar
        assertFalse(result.activeLeaves().contains("floor"));
    }

    // --- Version tracking ---

    @Test
    void versionSetTracksMultipleLeaves() {
        var leaf1 = new MarketDataLeaf("spot1", "SERIES_A", null, 0, null);
        var leaf2 = new MarketDataLeaf("spot2", "SERIES_B", null, 0, null);
        var expr = new Add(leaf1, leaf2);

        MarketDataPort port = new MarketDataPort() {
            @Override
            public MarketDataLookup lookupFixing(String series, Instant intervalStart) {
                long ver = "SERIES_A".equals(series) ? 5L : 10L;
                return new MarketDataLookup(new BigDecimal("42"), ver, series, intervalStart, null);
            }
            @Override
            public MarketDataLookup lookupIndex(String s, String r, DeliveryPeriod dp) { return null; }
            @Override
            public MarketDataLookup lookupForwardCurve(String s, YearMonth p, Instant a) { return null; }
            @Override
            public MarketDataLookup lookupFxRate(String c, Instant r) { return null; }
            @Override
            public MarketDataLookup lookupAtVersion(String s, Instant i, long v) { return null; }
        };

        PriceResolution result = evaluator.evaluate(expr, interval, ResolutionPurpose.FORWARD, port);
        assertEquals(5L, result.inputVersionSet().get("SERIES_A"));
        assertEquals(10L, result.inputVersionSet().get("SERIES_B"));
        assertEquals(2, result.activeLeaves().size());
    }

    // --- ConditionalPassThrough ---

    @Test
    void conditionalPassThroughWhenConditionMet() {
        var gateInput = new ConstantLeaf("gate", new BigDecimal("-10"), null);
        var inner = new ConstantLeaf("inner", new BigDecimal("85"), null);
        var expr = new ConditionalPassThrough(gateInput, "< 0", inner);

        PriceResolution result = evaluator.evaluate(expr, interval, ResolutionPurpose.FORWARD, stubPort(BigDecimal.ZERO, 1));
        // Condition met: pass through gate value (-10)
        assertEquals(0, new BigDecimal("-10").compareTo(result.value()));
    }

    @Test
    void conditionalPassThroughWhenConditionNotMet() {
        var gateInput = new ConstantLeaf("gate", new BigDecimal("10"), null);
        var inner = new ConstantLeaf("inner", new BigDecimal("85"), null);
        var expr = new ConditionalPassThrough(gateInput, "< 0", inner);

        PriceResolution result = evaluator.evaluate(expr, interval, ResolutionPurpose.FORWARD, stubPort(BigDecimal.ZERO, 1));
        // Condition not met: use inner
        assertEquals(0, new BigDecimal("85").compareTo(result.value()));
    }
}
