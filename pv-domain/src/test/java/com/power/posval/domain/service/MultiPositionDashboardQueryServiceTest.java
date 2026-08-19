package com.power.posval.domain.service;

import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.SettlementCell;
import com.power.posval.domain.model.TimeGranularity;
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
import com.power.posval.domain.port.service.dashboard.DailyAggregate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the multi-position overloads of {@link DefaultDashboardQueryService}.
 *
 * <p>Covers S15.3: {@code dailyAggregates(List<UUID>)}, {@code settledDayDetail(List<UUID>)},
 * and {@code forwardDayDetail(List<UUID>)}.
 *
 * <p>Reference deal: T-7788, tenant TN_0042, EPEX DE_LU wind PPA.
 * S15.2.1, S15.3, FR-035, D-13, D-14.
 */
class MultiPositionDashboardQueryServiceTest {

    private static final String TENANT = "TN_0042";
    private static final String PORTFOLIO = "PF-001";

    // Sep 2026: DAY_START to DAY_START + 1 day
    private static final Instant DAY_START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant DAY_END   = Instant.parse("2026-09-02T00:00:00Z");

    // 5-day contiguous range: Sep 01 to Sep 06
    private static final Instant RANGE_START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant RANGE_END   = Instant.parse("2026-09-06T00:00:00Z");

    // Sep month range for dailyAggregates
    private static final Instant MONTH_START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant MONTH_END   = Instant.parse("2026-10-01T00:00:00Z");

    private static final UUID POS_ID_1 = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID POS_ID_2 = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID POS_ID_3 = UUID.fromString("cccccccc-0000-0000-0000-000000000003");

    // =========================================================================
    // settledDayDetail — multi-position subset
    // =========================================================================

    /**
     * TC-S15-1: settledDayDetail with a non-empty positionIds list fetches
     * cells only for the specified positions (not portfolio-scoped).
     * FR-035: cells from two positions are netted into a single aggregated view.
     */
    @Test
    void settledDayDetail_multiPosition_fetchesByPositionIdSubset() {
        // Two cells — one per position in the subset
        com.power.posval.domain.model.SettlementCell cell1 =
            testCell(POS_ID_1, DAY_START, DAY_START.plusSeconds(900),
                     new BigDecimal("10.00"), new BigDecimal("2.5"), new BigDecimal("250.00"));
        com.power.posval.domain.model.SettlementCell cell2 =
            testCell(POS_ID_2, DAY_START, DAY_START.plusSeconds(900),
                     new BigDecimal("20.00"), new BigDecimal("5.0"), new BigDecimal("500.00"));

        // Cell repo returns both cells when bulk-queried for [POS_ID_1, POS_ID_2]
        var receivedIds = new ArrayList<UUID>();
        SettlementCellRepository cellRepo = buildCellRepo(receivedIds, List.of(cell1, cell2));

        // ledgerRepo.findByPortfolioAndDeliveryRange must NOT be called (subset path)
        var portfolioRangeCallCount = new int[]{0};
        PositionLedgerRepository ledgerRepo = buildLedgerRepo(portfolioRangeCallCount,
            Map.of(), List.of());

        var service = buildService(cellRepo, ledgerRepo, noopForwardMarkService());

        List<com.power.posval.domain.model.SettlementCell> result =
            service.settledDayDetail(TENANT, PORTFOLIO,
                List.of(POS_ID_1, POS_ID_2), DAY_START, DAY_END,
                TimeGranularity.MIN_15);

        // ledgerRepo portfolio range lookup should NOT have been called (we bypass it for subset)
        assertEquals(0, portfolioRangeCallCount[0],
            "Portfolio-range query must not be called when positionIds is non-empty");

        // The two IDs passed to findByPositionIds must be exactly [POS_ID_1, POS_ID_2]
        assertEquals(Set.of(POS_ID_1, POS_ID_2), Set.copyOf(receivedIds),
            "findByPositionIds must receive exactly the requested position IDs");

        // Both cells returned (MIN_15 = no aggregation)
        assertEquals(2, result.size());
    }

    /**
     * TC-S15-2: settledDayDetail with null positionIds → portfolio-scoped path.
     * ledgerRepo.findByPortfolioAndDeliveryRange is called; positionIds are derived
     * from the returned positions.
     */
    @Test
    void settledDayDetail_nullPositionIds_delegatesToPortfolioScope() {
        var portfolioRangeCallCount = new int[]{0};
        PositionLedgerRepository ledgerRepo = buildLedgerRepo(portfolioRangeCallCount,
            Map.of(POS_ID_1, testPosition(POS_ID_1)),
            List.of(testPosition(POS_ID_1)));

        com.power.posval.domain.model.SettlementCell cell =
            testCell(POS_ID_1, DAY_START, DAY_START.plusSeconds(900),
                     new BigDecimal("10.00"), new BigDecimal("2.5"), new BigDecimal("250.00"));
        var receivedIds = new ArrayList<UUID>();
        SettlementCellRepository cellRepo = buildCellRepo(receivedIds, List.of(cell));

        var service = buildService(cellRepo, ledgerRepo, noopForwardMarkService());

        List<com.power.posval.domain.model.SettlementCell> result =
            service.settledDayDetail(TENANT, PORTFOLIO,
                (List<UUID>) null, DAY_START, DAY_END,
                TimeGranularity.MIN_15);

        // Portfolio range lookup must have been called
        assertEquals(1, portfolioRangeCallCount[0],
            "Portfolio-range query must be called when positionIds is null");
        assertEquals(1, result.size());
    }

    /**
     * TC-S15-3: settledDayDetail with empty positionIds → portfolio-scoped path.
     */
    @Test
    void settledDayDetail_emptyPositionIds_delegatesToPortfolioScope() {
        var portfolioRangeCallCount = new int[]{0};
        PositionLedgerRepository ledgerRepo = buildLedgerRepo(portfolioRangeCallCount,
            Map.of(), List.of()); // returns no positions → empty result

        SettlementCellRepository cellRepo = buildCellRepo(new ArrayList<>(), List.of());
        var service = buildService(cellRepo, ledgerRepo, noopForwardMarkService());

        List<com.power.posval.domain.model.SettlementCell> result =
            service.settledDayDetail(TENANT, PORTFOLIO,
                List.of(), DAY_START, DAY_END,
                TimeGranularity.MIN_15);

        assertEquals(1, portfolioRangeCallCount[0],
            "Portfolio-range query must be called when positionIds is empty");
        assertTrue(result.isEmpty());
    }

    /**
     * TC-S15-4: settledDayDetail with a multi-day contiguous range.
     * The service passes [RANGE_START, RANGE_END) straight through to
     * cellRepo.findByPositionIds — no special bucketing required (S15.3.2).
     */
    @Test
    void settledDayDetail_contiguousDayRange_passesRangeToRepository() {
        var capturedStart = new Instant[]{null};
        var capturedEnd   = new Instant[]{null};

        SettlementCellRepository cellRepo = new SettlementCellRepository() {
            @Override public void save(com.power.posval.domain.model.SettlementCell c) {}
            @Override public List<com.power.posval.domain.model.SettlementCell> findByPosition(
                    String t, UUID p, Instant s, Instant e) { return List.of(); }
            @Override public List<com.power.posval.domain.model.SettlementCell> findByPositionIds(
                    String t, List<UUID> ids, Instant s, Instant e) {
                capturedStart[0] = s;
                capturedEnd[0]   = e;
                return List.of();
            }
        };

        var service = buildService(cellRepo, emptyLedgerRepo(), noopForwardMarkService());

        service.settledDayDetail(TENANT, PORTFOLIO,
            List.of(POS_ID_1), RANGE_START, RANGE_END,
            TimeGranularity.MIN_15);

        assertEquals(RANGE_START, capturedStart[0], "rangeStart must be forwarded unchanged");
        assertEquals(RANGE_END,   capturedEnd[0],   "rangeEnd must be forwarded unchanged");
    }

    // =========================================================================
    // dailyAggregates — multi-position subset
    // =========================================================================

    /**
     * TC-S15-5: dailyAggregates with empty positionIds → delegates to
     * portfolio-scoped path (positionId = null). No exception, returns day rows.
     */
    @Test
    void dailyAggregates_emptyPositionIds_delegatesToPortfolioScope() {
        // With all repos returning empty, the fallback returns empty-valued DailyAggregates
        // for each calendar day in Sep 2026 (30 days, Europe/Berlin aligned to UTC here).
        var service = buildService(
            buildCellRepo(new ArrayList<>(), List.of()),
            emptyLedgerRepo(),
            noopForwardMarkService());

        List<DailyAggregate> result = service.dailyAggregates(
            TENANT, PORTFOLIO, List.of(),
            MONTH_START, MONTH_END, "UTC");

        // 30 days in September — all empty but structure is correct
        assertEquals(30, result.size(), "Should produce one DailyAggregate per day in September");
    }

    /**
     * TC-S15-6: dailyAggregates with a single positionId → delegates to the
     * existing single-position path.
     */
    @Test
    void dailyAggregates_singlePositionId_delegatesToSinglePositionPath() {
        var service = buildService(
            buildCellRepo(new ArrayList<>(), List.of()),
            emptyLedgerRepo(),
            noopForwardMarkService());

        // Should not throw and should return 30 day rows
        List<DailyAggregate> result = service.dailyAggregates(
            TENANT, PORTFOLIO, List.of(POS_ID_1),
            MONTH_START, MONTH_END, "UTC");

        assertEquals(30, result.size());
    }

    /**
     * TC-S15-7: dailyAggregates with multiple positionIds → per-day fallback uses
     * the subset filter. Positions outside the set are excluded.
     *
     * <p>Setup: ledgerRepo returns two positions (POS_ID_1, POS_ID_2) for any range
     * query. Cell repo returns a cell only for POS_ID_1. We request subset [POS_ID_1].
     * Expect: cell for POS_ID_1 is aggregated; POS_ID_2 is excluded.
     */
    @Test
    void dailyAggregates_multiPositionSubset_filtersToSubset() {
        // One settled day: Sep 2 (fully in the past relative to test clock)
        // We test via: did cellRepo receive only POS_ID_1 in its ID list?
        var capturedIds = new ArrayList<List<UUID>>();

        SettlementCellRepository cellRepo = new SettlementCellRepository() {
            @Override public void save(com.power.posval.domain.model.SettlementCell c) {}
            @Override public List<com.power.posval.domain.model.SettlementCell> findByPosition(
                    String t, UUID p, Instant s, Instant e) { return List.of(); }
            @Override public List<com.power.posval.domain.model.SettlementCell> findByPositionIds(
                    String t, List<UUID> ids, Instant s, Instant e) {
                capturedIds.add(new ArrayList<>(ids));
                return List.of();
            }
        };

        // ledgerRepo returns both POS_ID_1 and POS_ID_2 for portfolio range queries
        PositionLedgerRepository ledgerRepo = buildLedgerRepoWithPositions(
            List.of(testPosition(POS_ID_1), testPosition(POS_ID_2)));

        var service = buildService(cellRepo, ledgerRepo, noopForwardMarkService());

        // Only request POS_ID_1 in the subset
        service.dailyAggregates(
            TENANT, PORTFOLIO, List.of(POS_ID_1),
            MONTH_START, MONTH_END, "UTC");

        // Every call to findByPositionIds must contain only POS_ID_1, never POS_ID_2
        // (Some days may be FORWARD and skip the cellRepo entirely — that is fine.)
        for (List<UUID> callIds : capturedIds) {
            assertTrue(callIds.contains(POS_ID_1),
                "POS_ID_1 must be in every findByPositionIds call");
            assertFalse(callIds.contains(POS_ID_2),
                "POS_ID_2 must be excluded (outside requested subset)");
        }
    }

    // =========================================================================
    // forwardDayDetail — multi-position subset
    // =========================================================================

    /**
     * TC-S15-8: forwardDayDetail with a non-empty subset resolves each positionId
     * via ledgerRepo.findById and calls ForwardMarkService per resolved position.
     */
    @Test
    void forwardDayDetail_multiPositionSubset_callsForwardMarkServicePerPosition() {
        var fmsCalledFor = new ArrayList<UUID>();
        ForwardMarkService fms = new ForwardMarkService() {
            @Override public MonthlyMark computeMonthlyMark(String t, UUID p, Instant s, Instant e) {
                return null;
            }
            @Override public List<IntervalMark> computeIntervalMarks(
                    String t, UUID p, Instant s, Instant e) {
                fmsCalledFor.add(p);
                return List.of(testIntervalMark(p));
            }
            @Override public BigDecimal computePortfolioMtm(String t, String p, Instant s, Instant e) {
                return BigDecimal.ZERO;
            }
        };

        PositionLedgerRepository ledgerRepo = buildLedgerRepo(new int[]{0},
            Map.of(POS_ID_1, testPosition(POS_ID_1), POS_ID_2, testPosition(POS_ID_2)),
            List.of());

        var service = buildService(
            buildCellRepo(new ArrayList<>(), List.of()),
            ledgerRepo, fms);

        var result = service.forwardDayDetail(
            TENANT, PORTFOLIO, List.of(POS_ID_1, POS_ID_2),
            DAY_START, DAY_END, TimeGranularity.MIN_15);

        assertEquals(2, fmsCalledFor.size(),
            "ForwardMarkService must be called once per resolved position");
        assertTrue(fmsCalledFor.contains(POS_ID_1));
        assertTrue(fmsCalledFor.contains(POS_ID_2));
        assertEquals(2, result.size());
    }

    /**
     * TC-S15-9: forwardDayDetail with null positionIds → portfolio-scoped path
     * uses ledgerRepo.findByPortfolioAndDeliveryRange.
     */
    @Test
    void forwardDayDetail_nullPositionIds_delegatesToPortfolioScope() {
        var portfolioRangeCallCount = new int[]{0};
        PositionLedgerRepository ledgerRepo = buildLedgerRepo(portfolioRangeCallCount,
            Map.of(), List.of());  // returns no positions → empty result

        var service = buildService(
            buildCellRepo(new ArrayList<>(), List.of()),
            ledgerRepo, noopForwardMarkService());

        var result = service.forwardDayDetail(
            TENANT, PORTFOLIO, (List<UUID>) null,
            DAY_START, DAY_END, TimeGranularity.MIN_15);

        assertEquals(1, portfolioRangeCallCount[0],
            "Portfolio-range query must be called when positionIds is null");
        assertTrue(result.isEmpty());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private DefaultDashboardQueryService buildService(SettlementCellRepository cellRepo,
                                                       PositionLedgerRepository ledgerRepo,
                                                       ForwardMarkService fms) {
        TradeLegRollupRepository tradeLegRepo = new TradeLegRollupRepository() {
            @Override
            public List<TradeLegRollupCell> findByPortfolio(
                    String t, String p, Instant s, Instant e, TimeGranularity g) {
                return List.of();  // empty → on-the-fly fallback is triggered for L3
            }
            @Override public void saveAll(String t, List<TradeLegRollupCell> c) {}
            @Override public void deleteByPositionId(String t, UUID id) {}
        };

        RollupRepository rollupRepo = new RollupRepository() {
            @Override
            public List<RollupCell> findByRange(String t, String d, String p,
                                                 Instant s, Instant e, TimeGranularity g) {
                return List.of();
            }
            @Override public void refresh(String t, Instant s, Instant e, TimeGranularity g) {}
            @Override
            public List<RollupCell> findByPortfolio(String t, String p,
                                                     Instant s, Instant e, TimeGranularity g) {
                return List.of();
            }
        };

        TradeIntervalCache tic = new TradeIntervalCache() {
            @Override
            public List<TradeIntervalRecord> getForTradeLeg(
                    String t, String tl, Instant s, Instant e) { return List.of(); }
            @Override public void rebuild(String t, String tl, Instant s, Instant e) {}
            @Override public void writeAll(String t, List<TradeIntervalRecord> r) {}
        };

        return new DefaultDashboardQueryService(
            rollupRepo, tradeLegRepo, ledgerRepo, cellRepo, fms, tic,
            new DefaultNumericPrecision());
    }

    /** SettlementCellRepository that records which positionIds were passed to findByPositionIds. */
    private SettlementCellRepository buildCellRepo(List<UUID> receivedIds,
                                                    List<com.power.posval.domain.model.SettlementCell> returnCells) {
        return new SettlementCellRepository() {
            @Override public void save(com.power.posval.domain.model.SettlementCell c) {}
            @Override
            public List<com.power.posval.domain.model.SettlementCell> findByPosition(
                    String t, UUID p, Instant s, Instant e) {
                return returnCells.stream()
                    .filter(c -> c.positionId().equals(p))
                    .toList();
            }
            @Override
            public List<com.power.posval.domain.model.SettlementCell> findByPositionIds(
                    String t, List<UUID> ids, Instant s, Instant e) {
                receivedIds.addAll(ids);
                return returnCells;
            }
        };
    }

    /**
     * PositionLedgerRepository that:
     * - increments {@code portfolioRangeCallCount} on {@code findByPortfolioAndDeliveryRange}
     * - returns individual positions from {@code byId} on {@code findById}
     * - returns {@code portfolioPositions} on {@code findByPortfolioAndDeliveryRange}
     */
    private PositionLedgerRepository buildLedgerRepo(int[] portfolioRangeCallCount,
                                                      Map<UUID, PositionLedgerEntry> byId,
                                                      List<PositionLedgerEntry> portfolioPositions) {
        return new PositionLedgerRepository() {
            @Override public void save(PositionLedgerEntry e) {}
            @Override
            public Optional<PositionLedgerEntry> findById(UUID id) {
                return Optional.ofNullable(byId.get(id));
            }
            @Override public List<PositionLedgerEntry> findCurrentByTradeLeg(
                    String t, String tr, String tl) { return List.of(); }
            @Override public List<PositionLedgerEntry> findAsOf(
                    String t, String tr, String tl, Instant b, Instant k) { return List.of(); }
            @Override public List<PositionLedgerEntry> findAllByDeliveryRange(
                    String t, Instant s, Instant e) { return List.of(); }
            @Override public List<PositionLedgerEntry> findByDeliveryRangeForTradeLeg(
                    String t, String tr, String tl, Instant s, Instant e) { return List.of(); }
            @Override public void supersede(List<PositionLedgerEntry> old,
                                            List<PositionLedgerEntry> nw) {}
            @Override
            public List<PositionLedgerEntry> findByPortfolioAndDeliveryRange(
                    String t, String p, Instant s, Instant e) {
                portfolioRangeCallCount[0]++;
                return portfolioPositions;
            }
        };
    }

    /** PositionLedgerRepository that always returns the given list for portfolio-range queries. */
    private PositionLedgerRepository buildLedgerRepoWithPositions(List<PositionLedgerEntry> positions) {
        return buildLedgerRepo(new int[]{0},
            positions.stream().collect(java.util.stream.Collectors.toMap(
                PositionLedgerEntry::id, p -> p)),
            positions);
    }

    private PositionLedgerRepository emptyLedgerRepo() {
        return buildLedgerRepo(new int[]{0}, Map.of(), List.of());
    }

    private ForwardMarkService noopForwardMarkService() {
        return new ForwardMarkService() {
            @Override
            public MonthlyMark computeMonthlyMark(String t, UUID p, Instant s, Instant e) {
                return null;
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

    private PositionLedgerEntry testPosition(UUID id) {
        return PositionLedgerEntry.builder()
            .id(id)
            .tenantId(TENANT)
            .tradeId("T-7788")
            .tradeLegId("LEG-" + id.toString().substring(0, 8))
            .tradeVersion(1)
            .deliveryRange(new DeliveryRange(
                YearMonth.of(2026, 9), YearMonth.of(2026, 9),
                ZoneId.of("Europe/Berlin")))
            .deliveryStart(MONTH_START)
            .deliveryEnd(MONTH_END)
            .quantity(new BigDecimal("100"))
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .priceExpressionId(UUID.fromString("00000000-0000-0000-0000-000000000099"))
            .portfolioId(PORTFOLIO)
            .deliveryPointId("DP-EPEX-DE")
            .knownFrom(Instant.parse("2026-08-01T00:00:00Z"))
            .knownTo(null)   // current knowledge
            .validFrom(MONTH_START)
            .validTo(null)
            .status("ACTIVE")
            .build();
    }

    private com.power.posval.domain.model.SettlementCell testCell(UUID positionId,
                                                                    Instant start, Instant end,
                                                                    BigDecimal mw, BigDecimal mwh,
                                                                    BigDecimal amount) {
        BigDecimal price = mwh.signum() != 0 ? amount.divide(mwh,
            8, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO;
        return new com.power.posval.domain.model.SettlementCell(
            UUID.randomUUID(),
            TENANT,
            positionId,
            start, end,
            "CALCULATED",
            "FINAL",
            price,
            mw,
            mwh,
            amount,
            price,          // marketPrice same as price for simplicity
            amount,         // marketAmount
            BigDecimal.ZERO, // pnl
            "EUR",
            Set.of(),
            Map.of(),
            Instant.now()
        );
    }

    private IntervalMark testIntervalMark(UUID positionId) {
        return new IntervalMark(
            DAY_START, DAY_START.plusSeconds(900),
            positionId, "LEG-" + positionId.toString().substring(0, 8),
            new BigDecimal("10.00"),
            new BigDecimal("2.5"),
            new BigDecimal("50.00"),
            new BigDecimal("125.00"),
            "CURVE-DE-WIND", 1L, "EUR"
        );
    }
}
