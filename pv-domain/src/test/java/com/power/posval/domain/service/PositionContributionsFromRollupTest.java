package com.power.posval.domain.service;

import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.SettlementCell;
import com.power.posval.domain.model.TimeGranularity;
import com.power.posval.domain.model.TradeDirection;
import com.power.posval.domain.model.VolumeUnit;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.port.DefaultNumericPrecision;
import com.power.posval.domain.port.cache.TradeIntervalCache;
import com.power.posval.domain.port.cache.TradeIntervalRecord;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import com.power.posval.domain.port.repository.RollupCell;
import com.power.posval.domain.port.repository.RollupRepository;
import com.power.posval.domain.port.repository.SettlementCellRepository;
import com.power.posval.domain.port.repository.TradeLegRollupCell;
import com.power.posval.domain.port.repository.TradeLegRollupRepository;
import com.power.posval.domain.port.service.ForwardMarkService;
import com.power.posval.domain.port.service.IntervalMark;
import com.power.posval.domain.port.service.MonthlyMark;
import com.power.posval.domain.port.service.dashboard.PositionContribution;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the refactored {@link DefaultDashboardQueryService#positionContributions}
 * that reads from {@link TradeLegRollupRepository} instead of on-the-fly computation.
 *
 * <p>Reference deal: T-7788, tenant TN_0042, EPEX DE_LU wind PPA.
 * S12.1, Pattern #18, Appendix B.
 */
class PositionContributionsFromRollupTest {

    private static final String TENANT = "TN_0042";
    private static final String PORTFOLIO = "PF-001";
    private static final Instant PERIOD_START = Instant.parse("2025-03-01T00:00:00Z");
    private static final Instant PERIOD_END = Instant.parse("2025-04-01T00:00:00Z");

    // -------------------------------------------------------------------------
    // Test case 1: rollup available — uses TradeLegRollupRepository
    // -------------------------------------------------------------------------

    @Test
    void positionContributions_rollupAvailable_mapsTradeLegRollupToContribution() {
        UUID posId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        var rollup = testRollupCell(posId, "SETTLED", false);

        // ForwardMarkService must NOT be called for SETTLED positions
        var fmsCallCount = new int[]{0};
        var fms = mockForwardMarkService(posId, null, fmsCallCount);

        var service = buildService(List.of(rollup), fms);

        List<PositionContribution> result = service.positionContributions(
            TENANT, PORTFOLIO, PERIOD_START, PERIOD_END);

        assertEquals(1, result.size());
        var contrib = result.get(0);
        assertEquals(posId, contrib.positionId());
        assertEquals("T-7788", contrib.tradeId());
        assertEquals("LEG-1", contrib.tradeLegId());
        assertEquals("SETTLED", contrib.deliveryStatus());
        assertEquals(0, new BigDecimal("49.0").compareTo(contrib.settledMw()));
        assertEquals(0, new BigDecimal("24.5").compareTo(contrib.settledMwh()));
        assertEquals(0, new BigDecimal("2082.50").compareTo(contrib.settledValue()));
        assertEquals("EUR", contrib.currency());

        // ForwardMarkService must NOT have been called for a SETTLED position
        assertEquals(0, fmsCallCount[0], "ForwardMarkService should not be called for SETTLED position");
    }

    // -------------------------------------------------------------------------
    // Test case 2: forward top-up — ForwardMarkService called only for hasForwardIntervals=true
    // -------------------------------------------------------------------------

    @Test
    void positionContributions_forwardTopUp_callsForwardMarkServiceOnlyForPartialOrForward() {
        UUID settledPosId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        UUID forwardPosId = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

        var settledRollup = testRollupCell(settledPosId, "LEG-SETTLED", "SETTLED", false);
        var forwardRollup = testRollupCell(forwardPosId, "LEG-PARTIAL", "PARTIAL", true);

        var forwardMark = new MonthlyMark(
            forwardPosId, PERIOD_START, PERIOD_END,
            new BigDecimal("5000.00"),   // forwardMtm
            new BigDecimal("50.0"),      // totalMwh
            new BigDecimal("100.00"),    // avgPrice
            "CURVE-DE-WIND", 1L, 1L, "EUR"
        );

        // Track which positions triggered ForwardMarkService
        var fmsCalledFor = new ArrayList<UUID>();
        ForwardMarkService fms = new ForwardMarkService() {
            @Override
            public MonthlyMark computeMonthlyMark(String t, UUID pid, Instant s, Instant e) {
                fmsCalledFor.add(pid);
                return pid.equals(forwardPosId) ? forwardMark : null;
            }
            @Override public List<IntervalMark> computeIntervalMarks(String t, UUID p, Instant s, Instant e) { return List.of(); }
            @Override public BigDecimal computePortfolioMtm(String t, String p, Instant s, Instant e) { return BigDecimal.ZERO; }
        };

        var service = buildService(List.of(settledRollup, forwardRollup), fms);

        List<PositionContribution> result = service.positionContributions(
            TENANT, PORTFOLIO, PERIOD_START, PERIOD_END);

        assertEquals(2, result.size());

        // ForwardMarkService called only for the PARTIAL/FORWARD position
        assertEquals(1, fmsCalledFor.size(),
            "ForwardMarkService should be called exactly once (for PARTIAL position)");
        assertEquals(forwardPosId, fmsCalledFor.get(0));

        // PARTIAL position should have forward mark value
        var forwardContrib = result.stream()
            .filter(c -> c.positionId().equals(forwardPosId))
            .findFirst()
            .orElseThrow();
        assertEquals(0, new BigDecimal("5000.00").compareTo(forwardContrib.forwardMarkValue()));
        assertEquals(0, new BigDecimal("50.0").compareTo(forwardContrib.forwardMwh()));
        assertEquals("PARTIAL", forwardContrib.deliveryStatus());

        // SETTLED position should have zero forward values
        var settledContrib = result.stream()
            .filter(c -> c.positionId().equals(settledPosId))
            .findFirst()
            .orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(settledContrib.forwardMarkValue()));
    }

    // -------------------------------------------------------------------------
    // Test case 3: empty rollup — returns empty list
    // -------------------------------------------------------------------------

    @Test
    void positionContributions_emptyRollup_fallsBackToOnTheFlySReturningEmpty() {
        // When rollup table is empty, fallback is invoked (OI-3).
        // Fallback uses ledgerRepo which also returns empty => empty result.
        var fmsCallCount = new int[]{0};
        var fms = mockForwardMarkService(null, null, fmsCallCount);

        var service = buildService(List.of(), fms);

        List<PositionContribution> result = service.positionContributions(
            TENANT, PORTFOLIO, PERIOD_START, PERIOD_END);

        assertTrue(result.isEmpty(), "Empty rollup should produce empty result");
    }

    // -------------------------------------------------------------------------
    // Test case 4: mixed SETTLED/PARTIAL/FORWARD — correct deliveryStatus pass-through
    // -------------------------------------------------------------------------

    @Test
    void positionContributions_mixedStatuses_passesDeliveryStatusThrough() {
        UUID p1 = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        UUID p2 = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
        UUID p3 = UUID.fromString("cccccccc-0000-0000-0000-000000000003");

        var settled  = testRollupCell(p1, "LEG-A", "SETTLED", false);
        var partial  = testRollupCell(p2, "LEG-B", "PARTIAL", true);
        var forward  = testRollupCell(p3, "LEG-C", "FORWARD", true);

        var fmsCallCount = new int[]{0};
        var fms = mockForwardMarkService(null, null, fmsCallCount);

        var service = buildService(List.of(settled, partial, forward), fms);

        List<PositionContribution> result = service.positionContributions(
            TENANT, PORTFOLIO, PERIOD_START, PERIOD_END);

        assertEquals(3, result.size());

        var statusMap = new HashMap<UUID, String>();
        for (var c : result) {
            statusMap.put(c.positionId(), c.deliveryStatus());
        }
        assertEquals("SETTLED", statusMap.get(p1));
        assertEquals("PARTIAL", statusMap.get(p2));
        assertEquals("FORWARD", statusMap.get(p3));

        // ForwardMarkService called for PARTIAL and FORWARD only
        assertEquals(2, fmsCallCount[0],
            "ForwardMarkService should be called for PARTIAL and FORWARD positions");
    }

    // -------------------------------------------------------------------------
    // Test case 5: stale rollup — resolves current position ID from ledger
    // -------------------------------------------------------------------------

    @Test
    void positionContributions_staleRollupPositionId_resolvesCurrentFromLedger() {
        // Rollup table has the OLD superseded position UUID (b1dcc703...).
        // Ledger now has a NEW current entry (f80eefff...) for the same trade leg.
        // positionContributions() must cross-check against the live ledger and
        // return the current (knownTo IS NULL) position ID, not the stale one.
        UUID stalePosId = UUID.fromString("b1dcc703-9e46-4a2c-989a-405c93a5815f");
        UUID currentPosId = UUID.fromString("f80eefff-4c4e-4a55-a083-d91307cca888");

        // Rollup was materialized with the old, now-superseded positionId
        var rollup = testRollupCell(stalePosId, "SETTLED", false);

        // Ledger returns the current (non-superseded) position for this trade leg
        var currentPosition = new PositionLedgerEntry.Builder()
            .id(currentPosId)
            .tenantId(TENANT)
            .tradeId("T-7788")
            .tradeLegId("LEG-1")
            .tradeVersion(2)
            .deliveryRange(new DeliveryRange(
                java.time.YearMonth.of(2025, 3), java.time.YearMonth.of(2025, 3),
                ZoneId.of("Europe/Berlin")))
            .deliveryStart(PERIOD_START)
            .deliveryEnd(PERIOD_END)
            .quantity(new BigDecimal("100"))
            .direction(TradeDirection.BUY)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .priceExpressionId(UUID.randomUUID())
            .validFrom(PERIOD_START)
            .knownFrom(Instant.parse("2025-03-15T10:00:00Z"))
            .portfolioId(PORTFOLIO)
            .build();

        var fmsCallCount = new int[]{0};
        var fms = mockForwardMarkService(null, null, fmsCallCount);

        var service = buildServiceWithLedger(List.of(rollup), fms, List.of(currentPosition));

        List<PositionContribution> result = service.positionContributions(
            TENANT, PORTFOLIO, PERIOD_START, PERIOD_END);

        assertEquals(1, result.size());
        assertEquals(currentPosId, result.get(0).positionId(),
            "Should resolve to the current (non-superseded) position ID from the live ledger");
        assertEquals("T-7788", result.get(0).tradeId());
        assertEquals("LEG-1", result.get(0).tradeLegId());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Constructs a {@link DefaultDashboardQueryService} with minimal mocks:
     * - {@link TradeLegRollupRepository} returns {@code rollups}
     * - All other repositories/caches return empty results
     * - Fallback (on-the-fly path) will return empty because ledgerRepo returns no positions
     */
    private DefaultDashboardQueryService buildService(List<TradeLegRollupCell> rollups,
                                                       ForwardMarkService fms) {
        TradeLegRollupRepository tradeLegRepo = new TradeLegRollupRepository() {
            @Override
            public List<TradeLegRollupCell> findByPortfolio(String t, String p, Instant s, Instant e, TimeGranularity g) {
                return new ArrayList<>(rollups);
            }
            @Override public void saveAll(String t, List<TradeLegRollupCell> c) {}
            @Override public void deleteByPositionId(String t, UUID id) {}
        };

        RollupRepository rollupRepo = new RollupRepository() {
            @Override public List<RollupCell> findByRange(String t, String d, String p, Instant s, Instant e, TimeGranularity g) { return List.of(); }
            @Override public void refresh(String t, Instant s, Instant e, TimeGranularity g) {}
        };

        PositionLedgerRepository ledgerRepo = new PositionLedgerRepository() {
            @Override public void save(PositionLedgerEntry e) {}
            @Override public Optional<PositionLedgerEntry> findById(UUID id) { return Optional.empty(); }
            @Override public List<PositionLedgerEntry> findCurrentByTradeLeg(String t, String tr, String tl) { return List.of(); }
            @Override public List<PositionLedgerEntry> findAsOf(String t, String tr, String tl, Instant b, Instant k) { return List.of(); }
            @Override public List<PositionLedgerEntry> findAllByDeliveryRange(String t, Instant s, Instant e) { return List.of(); }
            @Override public List<PositionLedgerEntry> findByDeliveryRangeForTradeLeg(String t, String tr, String tl, Instant s, Instant e) { return List.of(); }
            @Override public void supersede(List<PositionLedgerEntry> old, List<PositionLedgerEntry> nw) {}
            @Override public List<PositionLedgerEntry> findByPortfolioAndDeliveryRange(String t, String p, Instant s, Instant e) { return List.of(); }
        };

        SettlementCellRepository cellRepo = new SettlementCellRepository() {
            @Override public void save(SettlementCell c) {}
            @Override public List<SettlementCell> findByPosition(String t, UUID p, Instant s, Instant e) { return List.of(); }
        };

        TradeIntervalCache tic = new TradeIntervalCache() {
            @Override public List<TradeIntervalRecord> getForTradeLeg(String t, String tl, Instant s, Instant e) { return List.of(); }
            @Override public void rebuild(String t, String tl, Instant s, Instant e) {}
            @Override public void writeAll(String t, List<TradeIntervalRecord> r) {}
        };

        return new DefaultDashboardQueryService(
            rollupRepo, tradeLegRepo, ledgerRepo, cellRepo, fms, tic,
            new DefaultNumericPrecision());
    }

    /**
     * Like {@link #buildService} but with a ledger that returns the given current positions
     * from {@code findByPortfolioAndDeliveryRange} (bitemporal cross-check).
     */
    private DefaultDashboardQueryService buildServiceWithLedger(List<TradeLegRollupCell> rollups,
                                                                  ForwardMarkService fms,
                                                                  List<PositionLedgerEntry> currentPositions) {
        TradeLegRollupRepository tradeLegRepo = new TradeLegRollupRepository() {
            @Override
            public List<TradeLegRollupCell> findByPortfolio(String t, String p, Instant s, Instant e, TimeGranularity g) {
                return new ArrayList<>(rollups);
            }
            @Override public void saveAll(String t, List<TradeLegRollupCell> c) {}
            @Override public void deleteByPositionId(String t, UUID id) {}
        };

        RollupRepository rollupRepo = new RollupRepository() {
            @Override public List<RollupCell> findByRange(String t, String d, String p, Instant s, Instant e, TimeGranularity g) { return List.of(); }
            @Override public void refresh(String t, Instant s, Instant e, TimeGranularity g) {}
        };

        PositionLedgerRepository ledgerRepo = new PositionLedgerRepository() {
            @Override public void save(PositionLedgerEntry e) {}
            @Override public Optional<PositionLedgerEntry> findById(UUID id) { return Optional.empty(); }
            @Override public List<PositionLedgerEntry> findCurrentByTradeLeg(String t, String tr, String tl) { return List.of(); }
            @Override public List<PositionLedgerEntry> findAsOf(String t, String tr, String tl, Instant b, Instant k) { return List.of(); }
            @Override public List<PositionLedgerEntry> findAllByDeliveryRange(String t, Instant s, Instant e) { return List.of(); }
            @Override public List<PositionLedgerEntry> findByDeliveryRangeForTradeLeg(String t, String tr, String tl, Instant s, Instant e) { return List.of(); }
            @Override public void supersede(List<PositionLedgerEntry> old, List<PositionLedgerEntry> nw) {}
            @Override public List<PositionLedgerEntry> findByPortfolioAndDeliveryRange(String t, String p, Instant s, Instant e) {
                return new ArrayList<>(currentPositions);
            }
        };

        SettlementCellRepository cellRepo = new SettlementCellRepository() {
            @Override public void save(SettlementCell c) {}
            @Override public List<SettlementCell> findByPosition(String t, UUID p, Instant s, Instant e) { return List.of(); }
        };

        TradeIntervalCache tic = new TradeIntervalCache() {
            @Override public List<TradeIntervalRecord> getForTradeLeg(String t, String tl, Instant s, Instant e) { return List.of(); }
            @Override public void rebuild(String t, String tl, Instant s, Instant e) {}
            @Override public void writeAll(String t, List<TradeIntervalRecord> r) {}
        };

        return new DefaultDashboardQueryService(
            rollupRepo, tradeLegRepo, ledgerRepo, cellRepo, fms, tic,
            new DefaultNumericPrecision());
    }

    private TradeLegRollupCell testRollupCell(UUID positionId, String deliveryStatus,
                                               boolean hasForwardIntervals) {
        return testRollupCell(positionId, "LEG-1", deliveryStatus, hasForwardIntervals);
    }

    private TradeLegRollupCell testRollupCell(UUID positionId, String tradeLegId,
                                               String deliveryStatus,
                                               boolean hasForwardIntervals) {
        return new TradeLegRollupCell(
            positionId,
            TENANT,
            "T-7788",
            tradeLegId,
            1,
            "DP-EPEX-DE",
            PORTFOLIO,
            PERIOD_START,
            PERIOD_END,
            TimeGranularity.MONTHLY,
            new BigDecimal("49.00000000"),        // settledMw (TWA)
            new BigDecimal("24.50000000"),        // settledMwh
            new BigDecimal("84.99489796"),        // avgPrice (volume-weighted)
            new BigDecimal("2082.5000"),          // settledValue
            new BigDecimal("710.0000"),           // marketValue
            new BigDecimal("-1372.5000"),         // realizedPnl
            hasForwardIntervals,
            deliveryStatus,
            new BigDecimal("100.00000000"),       // quantity
            "MW_CAPACITY",
            "BUY",                                // direction (FR-034)
            "EUR",
            "abc123",
            Instant.parse("2025-03-15T10:00:00Z")
        );
    }

    private ForwardMarkService mockForwardMarkService(UUID positionId,
                                                       MonthlyMark mark,
                                                       int[] callCount) {
        return new ForwardMarkService() {
            @Override
            public MonthlyMark computeMonthlyMark(String t, UUID pid, Instant s, Instant e) {
                callCount[0]++;
                return (positionId != null && pid.equals(positionId)) ? mark : null;
            }
            @Override
            public List<IntervalMark> computeIntervalMarks(String t, UUID p, Instant s, Instant e) {
                return List.of();
            }
            @Override
            public BigDecimal computePortfolioMtm(String t, String p, Instant s, Instant e) {
                return BigDecimal.ZERO;
            }
        };
    }
}
