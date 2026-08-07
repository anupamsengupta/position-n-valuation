package com.power.posval.app.pricing;

import com.ctrm.ruleengine.api.RuleEngine;
import com.ctrm.ruleengine.core.DefaultRuleEngine;
import com.ctrm.ruleengine.core.cache.InMemoryRuleCache;
import com.ctrm.ruleengine.core.conflict.DefaultConflictResolver;
import com.ctrm.ruleengine.core.dao.json.JsonRuleDao;
import com.ctrm.ruleengine.core.dao.json.JsonRuleSetDao;
import com.ctrm.ruleengine.core.eval.DefaultDecisionEvaluator;
import com.ctrm.ruleengine.core.failure.DefaultFailureHandler;
import com.ctrm.ruleengine.core.resolve.CacheBackedRuleResolver;
import com.ctrm.ruleengine.model.Rule;
import com.ctrm.ruleengine.model.RuleSet;
import com.ctrm.ruleengine.mvel.MvelEvaluator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.power.posval.domain.model.expression.PriceExpression;
import com.power.posval.domain.port.DefaultNumericPrecision;
import com.power.posval.domain.port.NumericPrecision;
import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.marketdata.MarketDataPort;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.service.PriceExpressionBasedEvaluator;
import com.power.posval.domain.service.PriceResolution;
import com.power.posval.domain.service.ResolutionPurpose;
import com.power.posval.domain.service.stub.JsonPriceExpressionRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for {@link RuleEngineBasedEvaluator}.
 *
 * Wires the real rule engine from JSON stub files, evaluates all 5 EXPR
 * formulas, and validates:
 *   1. Correct numeric results
 *   2. Correct input version tracking
 *   3. Bit-identical precision vs the expression-tree evaluator
 *
 * No Spring context required — manual wiring mirrors RuleEngineConfig.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuleEngineBasedEvaluatorTest {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");
    private static final NumericPrecision NP = new DefaultNumericPrecision();

    // Both evaluators use the same NumericPrecision for parity testing
    private RuleEngineBasedEvaluator ruleEngineEvaluator;
    private PriceExpressionBasedEvaluator treeWalkerEvaluator;
    private JsonPriceExpressionRepository exprRepo;
    private DeliveryPeriod interval;

    @BeforeAll
    void wireRuleEngine() throws IOException {
        // --- Expression repository (shared by both evaluators) ---
        exprRepo = new JsonPriceExpressionRepository();

        // --- Tree-walker evaluator (baseline) ---
        treeWalkerEvaluator = new PriceExpressionBasedEvaluator(NP);

        // --- Rule engine evaluator ---
        RuleEngine engine = buildRuleEngine();
        List<PriceRuleDefinition> definitions = loadDefinitions();

        // Reverse map: PriceExpression → exprId
        Map<PriceExpression, String> expressionToId = new HashMap<>();
        for (PriceRuleDefinition def : definitions) {
            UUID id = UUID.fromString(def.priceExpressionId());
            exprRepo.findById(id).ifPresent(
                expr -> expressionToId.put(expr, def.priceExpressionId()));
        }

        // Definition lookup: exprId → PriceRuleDefinition
        Map<String, PriceRuleDefinition> definitionsByExprId = new HashMap<>();
        for (PriceRuleDefinition def : definitions) {
            definitionsByExprId.put(def.priceExpressionId(), def);
        }

        ruleEngineEvaluator = new RuleEngineBasedEvaluator(
            engine, expressionToId, definitionsByExprId, NP);

        // --- Shared test interval ---
        interval = new DeliveryPeriod(
            ZonedDateTime.of(2025, 3, 1, 0, 0, 0, 0, CET),
            ZonedDateTime.of(2025, 3, 1, 1, 0, 0, 0, CET),
            CET);
    }

    // =====================================================================
    //  EXPR-1: Fixed price (85 EUR/MWh)
    // =====================================================================

    @Test
    void expr1_fixedPrice() {
        PriceExpression expr = exprRepo.findById(exprId(1)).orElseThrow();
        MarketDataPort port = stubPort(BigDecimal.ZERO, 1L);

        PriceResolution result = ruleEngineEvaluator.evaluate(
            expr, interval, ResolutionPurpose.SETTLEMENT, port);

        assertEquals(0, new BigDecimal("85.00000000").compareTo(result.value()),
            "Fixed price should be 85.00 rounded to PRICE scale 8");
        assertTrue(result.inputVersionSet().isEmpty(),
            "No market data lookups for a fixed-price formula");
    }

    // =====================================================================
    //  EXPR-2: Index + premium (EPEX_DA15 + 3.20)
    // =====================================================================

    @Test
    void expr2_indexPlusPremium() {
        PriceExpression expr = exprRepo.findById(exprId(2)).orElseThrow();

        // EPEX_DA15_SETTLE returns 24.86 for settlement
        MarketDataPort port = stubPort(new BigDecimal("24.86"), 7L);

        PriceResolution result = ruleEngineEvaluator.evaluate(
            expr, interval, ResolutionPurpose.SETTLEMENT, port);

        // 24.86 + 3.20 = 28.06
        assertEquals(0, new BigDecimal("28.06000000").compareTo(result.value()),
            "Index + premium: 24.86 + 3.20 = 28.06");
        assertEquals(7L, result.inputVersionSet().get("EPEX_DA15_SETTLE"),
            "Settlement series version should be tracked");
    }

    @Test
    void expr2_forwardUsesForwardSeries() {
        PriceExpression expr = exprRepo.findById(exprId(2)).orElseThrow();

        // Configure port that returns different values per series
        MarketDataPort port = new MarketDataPort() {
            @Override
            public MarketDataLookup lookupFixing(String series, Instant intervalStart) {
                BigDecimal val = "EPEX_DA15".equals(series)
                    ? new BigDecimal("50.00") : new BigDecimal("24.86");
                return new MarketDataLookup(val, 5L, series, intervalStart, null);
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

        PriceResolution result = ruleEngineEvaluator.evaluate(
            expr, interval, ResolutionPurpose.FORWARD, port);

        // Forward uses EPEX_DA15 (50.00), not EPEX_DA15_SETTLE
        assertEquals(0, new BigDecimal("53.20000000").compareTo(result.value()),
            "Forward: 50.00 + 3.20 = 53.20");
        assertEquals(5L, result.inputVersionSet().get("EPEX_DA15"),
            "Forward series version should be tracked");
    }

    // =====================================================================
    //  EXPR-3: Collar PPA — clamp(42, 95, EPEX_DA15)
    // =====================================================================

    @Test
    void expr3_collarInsideRange() {
        PriceExpression expr = exprRepo.findById(exprId(3)).orElseThrow();
        MarketDataPort port = stubPort(new BigDecimal("68.00"), 3L);

        PriceResolution result = ruleEngineEvaluator.evaluate(
            expr, interval, ResolutionPurpose.SETTLEMENT, port);

        assertEquals(0, new BigDecimal("68.00000000").compareTo(result.value()),
            "Inside collar: value passes through unchanged");
    }

    @Test
    void expr3_collarFloorBinds() {
        PriceExpression expr = exprRepo.findById(exprId(3)).orElseThrow();
        MarketDataPort port = stubPort(new BigDecimal("20.00"), 3L);

        PriceResolution result = ruleEngineEvaluator.evaluate(
            expr, interval, ResolutionPurpose.SETTLEMENT, port);

        assertEquals(0, new BigDecimal("42.00000000").compareTo(result.value()),
            "Below floor: clamped to 42");
    }

    @Test
    void expr3_collarCapBinds() {
        PriceExpression expr = exprRepo.findById(exprId(3)).orElseThrow();
        MarketDataPort port = stubPort(new BigDecimal("150.00"), 3L);

        PriceResolution result = ruleEngineEvaluator.evaluate(
            expr, interval, ResolutionPurpose.SETTLEMENT, port);

        assertEquals(0, new BigDecimal("95.00000000").compareTo(result.value()),
            "Above cap: clamped to 95");
    }

    // =====================================================================
    //  EXPR-4: CPI-escalated collar with negative-price gate
    //  if(EPEX < 0) → 0, else clamp(38, 110, 72 * (HICP / 108.7))
    // =====================================================================

    @Test
    void expr4_negativePriceGateFires() {
        PriceExpression expr = exprRepo.findById(exprId(4)).orElseThrow();
        MarketDataPort port = multiSeriesPort(
            new BigDecimal("-5.00"),   // EPEX_DA15 (negative → gate fires)
            new BigDecimal("112.30")); // HICP-DE (unused when gate fires)

        PriceResolution result = ruleEngineEvaluator.evaluate(
            expr, interval, ResolutionPurpose.SETTLEMENT, port);

        assertEquals(0, BigDecimal.ZERO.compareTo(result.value()),
            "Negative price triggers zero override");
    }

    @Test
    void expr4_positivePriceEscalatedCollar() {
        PriceExpression expr = exprRepo.findById(exprId(4)).orElseThrow();
        MarketDataPort port = multiSeriesPort(
            new BigDecimal("50.00"),   // EPEX_DA15 (positive → no gate)
            new BigDecimal("112.30")); // HICP-DE

        PriceResolution result = ruleEngineEvaluator.evaluate(
            expr, interval, ResolutionPurpose.SETTLEMENT, port);

        // 72 * (112.30 / 108.70) at INTERMEDIATE scale 10 = 1.0331186753
        // 72 * 1.0331186753 at PRICE scale 8 = 74.38454462
        // clamp(38, 110, 74.38454462) = 74.38454462
        assertEquals(0, new BigDecimal("74.38454462").compareTo(result.value()),
            "Escalated price inside collar: 72 * (112.30 / 108.70)");
    }

    @Test
    void expr4_escalatedPriceClampedAtFloor() {
        PriceExpression expr = exprRepo.findById(exprId(4)).orElseThrow();
        // Very low HICP → escalated price drops below floor 38
        MarketDataPort port = multiSeriesPort(
            new BigDecimal("50.00"),   // EPEX_DA15
            new BigDecimal("50.00"));  // HICP-DE (very low)

        PriceResolution result = ruleEngineEvaluator.evaluate(
            expr, interval, ResolutionPurpose.SETTLEMENT, port);

        // 72 * (50 / 108.7) = 72 * 0.4600... = 33.12... → clamped to 38
        assertEquals(0, new BigDecimal("38.00000000").compareTo(result.value()),
            "Escalated price below floor: clamped to 38");
    }

    @Test
    void expr4_escalatedPriceClampedAtCap() {
        PriceExpression expr = exprRepo.findById(exprId(4)).orElseThrow();
        // Very high HICP → escalated price exceeds cap 110
        MarketDataPort port = multiSeriesPort(
            new BigDecimal("50.00"),    // EPEX_DA15
            new BigDecimal("200.00"));  // HICP-DE (very high)

        PriceResolution result = ruleEngineEvaluator.evaluate(
            expr, interval, ResolutionPurpose.SETTLEMENT, port);

        // 72 * (200 / 108.7) = 72 * 1.8399... = 132.47... → clamped to 110
        assertEquals(0, new BigDecimal("110.00000000").compareTo(result.value()),
            "Escalated price above cap: clamped to 110");
    }

    // =====================================================================
    //  EXPR-5: Cross-border FX — (NORDPOOL - 12) * EUR/NOK rate
    // =====================================================================

    @Test
    void expr5_fxConversion() {
        PriceExpression expr = exprRepo.findById(exprId(5)).orElseThrow();
        MarketDataPort port = fxPort(
            new BigDecimal("450.00"),   // NORDPOOL_SYS (NOK/MWh)
            new BigDecimal("0.0893"));  // EUR/NOK rate

        PriceResolution result = ruleEngineEvaluator.evaluate(
            expr, interval, ResolutionPurpose.SETTLEMENT, port);

        // (450 - 12) * 0.0893 = 438 * 0.0893 = 39.1134
        // np.monetary rounds to MONETARY scale 4 → 39.1134
        // then final np.round to PRICE scale 8 → 39.11340000
        assertEquals(0, new BigDecimal("39.11340000").compareTo(result.value()),
            "FX conversion: (450 - 12) * 0.0893 = 39.1134");
        assertEquals(10L, result.inputVersionSet().get("NORDPOOL_SYS_SETTLE"),
            "NORDPOOL settlement series version tracked");
        assertEquals(20L, result.inputVersionSet().get("EUR/NOK"),
            "EUR/NOK FX rate version tracked");
    }

    // =====================================================================
    //  Parity tests: rule-engine vs tree-walker produce identical results
    // =====================================================================

    @Test
    void parity_expr1_fixedPrice() {
        assertParityForExpr(1, stubPort(BigDecimal.ZERO, 1L));
    }

    @Test
    void parity_expr2_indexPlusPremium() {
        assertParityForExpr(2, stubPort(new BigDecimal("24.86"), 7L));
    }

    @Test
    void parity_expr3_collarInside() {
        assertParityForExpr(3, stubPort(new BigDecimal("68.00"), 3L));
    }

    @Test
    void parity_expr3_collarFloor() {
        assertParityForExpr(3, stubPort(new BigDecimal("20.00"), 3L));
    }

    @Test
    void parity_expr3_collarCap() {
        assertParityForExpr(3, stubPort(new BigDecimal("150.00"), 3L));
    }

    @Test
    void parity_expr4_negativePriceGate() {
        assertParityForExpr(4, multiSeriesPort(
            new BigDecimal("-5.00"), new BigDecimal("112.30")));
    }

    @Test
    void parity_expr4_normalEscalation() {
        assertParityForExpr(4, multiSeriesPort(
            new BigDecimal("50.00"), new BigDecimal("112.30")));
    }

    @Test
    void parity_expr5_fxConversion() {
        assertParityForExpr(5, fxPort(
            new BigDecimal("450.00"), new BigDecimal("0.0893")));
    }

    // =====================================================================
    //  Error cases
    // =====================================================================

    @Test
    void unknownExpressionThrows() {
        // An expression tree not in the reverse map
        var unknownExpr = new com.power.posval.domain.model.expression.ConstantLeaf(
            "unknown", new BigDecimal("99"), "EUR/MWh");

        assertThrows(IllegalArgumentException.class,
            () -> ruleEngineEvaluator.evaluate(
                unknownExpr, interval, ResolutionPurpose.SETTLEMENT,
                stubPort(BigDecimal.ZERO, 1L)),
            "Should throw for expression not in rule definitions");
    }

    // =====================================================================
    //  Helpers
    // =====================================================================

    private void assertParityForExpr(int exprNum, MarketDataPort port) {
        PriceExpression expr = exprRepo.findById(exprId(exprNum)).orElseThrow();

        PriceResolution treeResult = treeWalkerEvaluator.evaluate(
            expr, interval, ResolutionPurpose.SETTLEMENT, port);
        PriceResolution ruleResult = ruleEngineEvaluator.evaluate(
            expr, interval, ResolutionPurpose.SETTLEMENT, port);

        assertEquals(0, treeResult.value().compareTo(ruleResult.value()),
            "EXPR-" + exprNum + " parity: tree-walker=" + treeResult.value()
            + " rule-engine=" + ruleResult.value());
    }

    private static UUID exprId(int num) {
        return UUID.fromString(
            String.format("00000000-0000-0000-0000-%012d", num));
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

    /**
     * Port for EXPR-4: returns different values per series type.
     * Fixings (EPEX) get epexValue, indexes (HICP) get hicpValue.
     */
    private MarketDataPort multiSeriesPort(BigDecimal epexValue, BigDecimal hicpValue) {
        return new MarketDataPort() {
            @Override
            public MarketDataLookup lookupFixing(String series, Instant intervalStart) {
                return new MarketDataLookup(epexValue, 1L, series, intervalStart, null);
            }
            @Override
            public MarketDataLookup lookupIndex(String series, String refMonthExpr, DeliveryPeriod dp) {
                return new MarketDataLookup(hicpValue, 2L, series, dp.start().toInstant(), null);
            }
            @Override
            public MarketDataLookup lookupForwardCurve(String s, YearMonth p, Instant a) {
                return new MarketDataLookup(BigDecimal.ZERO, 0L, s, a, null);
            }
            @Override
            public MarketDataLookup lookupFxRate(String c, Instant r) {
                return new MarketDataLookup(BigDecimal.ONE, 0L, c, r, null);
            }
            @Override
            public MarketDataLookup lookupAtVersion(String s, Instant i, long v) {
                return lookupFixing(s, i);
            }
        };
    }

    /**
     * Port for EXPR-5: returns different values for fixing vs FX rate.
     * Note: the tree-walker's FxConvert uses MarketDataLeaf for the FX rate,
     * which calls lookupFixing(). The rule-engine calls lookupFxRate().
     * This port handles both paths so parity tests work.
     */
    private MarketDataPort fxPort(BigDecimal nordpoolValue, BigDecimal fxRate) {
        return new MarketDataPort() {
            @Override
            public MarketDataLookup lookupFixing(String series, Instant intervalStart) {
                // Tree-walker resolves FX via MarketDataLeaf → lookupFixing
                BigDecimal val = series.contains("EUR/NOK") ? fxRate : nordpoolValue;
                long ver = series.contains("EUR/NOK") ? 20L : 10L;
                return new MarketDataLookup(val, ver, series, intervalStart, null);
            }
            @Override
            public MarketDataLookup lookupIndex(String s, String r, DeliveryPeriod dp) {
                return new MarketDataLookup(BigDecimal.ZERO, 0L, s, dp.start().toInstant(), null);
            }
            @Override
            public MarketDataLookup lookupForwardCurve(String s, YearMonth p, Instant a) {
                return new MarketDataLookup(BigDecimal.ZERO, 0L, s, a, null);
            }
            @Override
            public MarketDataLookup lookupFxRate(String currencyPair, Instant referenceDate) {
                return new MarketDataLookup(fxRate, 20L, currencyPair, referenceDate, null);
            }
            @Override
            public MarketDataLookup lookupAtVersion(String s, Instant i, long v) {
                return lookupFixing(s, i);
            }
        };
    }

    // --- Engine wiring (mirrors RuleEngineConfig without Spring) ---

    private RuleEngine buildRuleEngine() throws IOException {
        Path rulesPath = extractResource("stub/price-rules.json");
        JsonRuleDao ruleDao = new JsonRuleDao(rulesPath);

        InMemoryRuleCache cache = new InMemoryRuleCache();
        for (Rule rule : ruleDao.findAll()) {
            cache.put(rule);
        }
        cache.markWarm();

        Path rulesetsPath = extractResource("stub/price-rulesets.json");
        JsonRuleSetDao ruleSetDao = new JsonRuleSetDao(rulesetsPath);
        CacheBackedRuleResolver resolver = new CacheBackedRuleResolver(cache);
        for (RuleSet rs : ruleSetDao.findAll()) {
            resolver.index(rs);
        }

        return new DefaultRuleEngine(
            resolver,
            new DefaultDecisionEvaluator(
                new DefaultConflictResolver(),
                new DefaultFailureHandler()),
            List.of(new MvelEvaluator()));
    }

    private List<PriceRuleDefinition> loadDefinitions() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("stub/price-rule-definitions.json")) {
            return mapper.readValue(is, new TypeReference<>() {});
        }
    }

    private Path extractResource(String classpathLocation) throws IOException {
        Path tempFile = Files.createTempFile("rule-engine-test-", ".json");
        tempFile.toFile().deleteOnExit();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream(classpathLocation);
             var out = Files.newOutputStream(tempFile)) {
            in.transferTo(out);
        }
        return tempFile;
    }
}
