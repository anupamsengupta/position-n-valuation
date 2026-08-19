package com.power.posval.domain.service;

import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.SettlementCell;
import com.power.posval.domain.model.TimeGranularity;
import com.power.posval.domain.port.NumericPrecision;
import com.power.posval.domain.port.cache.TradeIntervalCache;
import com.power.posval.domain.port.cache.TradeIntervalRecord;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import com.power.posval.domain.port.repository.RollupCell;
import com.power.posval.domain.port.repository.RollupRepository;
import com.power.posval.domain.port.repository.SettlementCellRepository;
import com.power.posval.domain.port.repository.TradeLegRollupCell;
import com.power.posval.domain.port.repository.TradeLegRollupRepository;
import com.power.posval.domain.port.service.DashboardQueryService;
import com.power.posval.domain.port.service.ForwardMarkService;
import com.power.posval.domain.port.service.IntervalMark;
import com.power.posval.domain.port.service.MonthlyMark;
import com.power.posval.domain.port.service.dashboard.DailyAggregate;
import com.power.posval.domain.port.service.dashboard.ForwardIntervalDetail;
import com.power.posval.domain.port.service.dashboard.PortfolioSummary;
import com.power.posval.domain.port.service.dashboard.PositionContribution;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link DashboardQueryService}.
 *
 * <p>Implements the hybrid bulk-fetch approach (§6.5) for L3:
 * 3 bulk SQL queries + ~N Redis lookups via ForwardMarkService (ADR-002)
 * instead of per-position queries. FR-035 aggregation (TWA for MW, sum for
 * MWh, volume-weighted average for prices) is applied in Java per position.
 *
 * <p>D-13: Constructor injection via {@code @jakarta.inject.Inject}. No Spring
 * types. All BigDecimal arithmetic uses {@code NumericPrecision} per FR-036.
 *
 * <p>Pattern #18 (Port + Adapter), §6.5.
 */
public class DefaultDashboardQueryService implements DashboardQueryService {

    private static final Logger log = LoggerFactory.getLogger(DefaultDashboardQueryService.class);

    private static final String DEFAULT_TIMEZONE = "Europe/Berlin";

    private final RollupRepository rollupRepo;
    private final TradeLegRollupRepository tradeLegRollupRepo;
    private final PositionLedgerRepository ledgerRepo;
    private final SettlementCellRepository cellRepo;
    private final ForwardMarkService forwardMarkService;
    private final TradeIntervalCache tradeIntervalCache;
    private final NumericPrecision np;

    /** §6.5, D-13: constructor injection only. */
    @Inject
    public DefaultDashboardQueryService(RollupRepository rollupRepo,
                                         TradeLegRollupRepository tradeLegRollupRepo,
                                         PositionLedgerRepository ledgerRepo,
                                         SettlementCellRepository cellRepo,
                                         ForwardMarkService forwardMarkService,
                                         TradeIntervalCache tradeIntervalCache,
                                         NumericPrecision np) {
        this.rollupRepo = rollupRepo;
        this.tradeLegRollupRepo = tradeLegRollupRepo;
        this.ledgerRepo = ledgerRepo;
        this.cellRepo = cellRepo;
        this.forwardMarkService = forwardMarkService;
        this.tradeIntervalCache = tradeIntervalCache;
        this.np = np;
    }

    // -------------------------------------------------------------------------
    // L0: Portfolio List
    // -------------------------------------------------------------------------

    @Override
    public List<String> listPortfolios(String tenantId) {
        return ledgerRepo.findDistinctPortfolios(tenantId);
    }

    // -------------------------------------------------------------------------
    // L1: Portfolio Cards
    // -------------------------------------------------------------------------

    /**
     * Q-1: Aggregates rollup cells per currency for settled data, plus
     * on-the-fly forward computation via ForwardMarkService + TradeIntervalCache.
     *
     * <p>Settled data comes from materialized rollup cells (S7). Forward data
     * is computed on-the-fly from positions (S1) whose delivery extends beyond
     * {@code now} — same hybrid approach as L3 {@link #positionContributions}.
     * This avoids the gap where forward periods have no rollup cells because
     * no {@code SettlementComputed} event has fired for undelivered intervals.
     *
     * <p>FR-035: MW = TWA, MWh = sum, amounts = sum.
     */
    @Override
    public List<PortfolioSummary> portfolioSummaries(String tenantId,
                                                      String portfolioId,
                                                      Instant rangeStart,
                                                      Instant rangeEnd,
                                                      TimeGranularity rollupGranularity) {
        Instant now = Instant.now();

        // --- Step 1: Settled data from rollup cells (S7) ---
        List<RollupCell> cells = rollupRepo.findByPortfolio(
            tenantId, portfolioId, rangeStart, rangeEnd, rollupGranularity);

        // Accumulate rollup data by currency.
        // All rollup cells contribute to "settled" accumulators because rollup cells
        // only contain materialized settlement data — settlement events only fire
        // for delivered (past) intervals. A transition month (e.g., Aug 2026 when
        // today is Aug 15) has a rollup cell covering the full month, but its
        // pnl/settledValue/netMw/netMwh only reflect the settled portion (Aug 1-15).
        // Forward data is computed on-the-fly in Step 2 below.
        Map<String, SettledAccumulator> settledByCurrency = new LinkedHashMap<>();
        for (RollupCell cell : cells) {
            String currency = cell.currency() != null ? cell.currency() : "EUR";
            SettledAccumulator acc = settledByCurrency.computeIfAbsent(
                currency, k -> new SettledAccumulator());

            if (cell.pnl() != null) {
                acc.realizedPnl = acc.realizedPnl.add(cell.pnl());
            }
            // TWA weighting: use the settled portion of the period, not the full
            // period span. For a transition month (e.g., Aug when today is Aug 16),
            // the rollup cell covers Aug 1 - Sep 1 but only contains settlement
            // data for Aug 1 - Aug 16. Using the full month duration would under-
            // weight the transition month's MW average relative to fully-settled
            // months. Clamping periodEnd to min(periodEnd, now) gives the correct
            // settled duration for TWA recombination across cells.
            if (cell.periodStart() != null && cell.periodEnd() != null) {
                Instant effectiveEnd = cell.periodEnd().isAfter(now)
                    ? now : cell.periodEnd();
                long minutes = Duration.between(cell.periodStart(), effectiveEnd).toMinutes();
                if (minutes > 0) {
                    if (cell.netMw() != null) {
                        acc.mwWeightedSum = acc.mwWeightedSum.add(
                            cell.netMw().multiply(BigDecimal.valueOf(minutes)));
                    }
                    acc.totalMinutes += minutes;
                }
            }
            if (cell.netMwh() != null) {
                acc.netMwh = acc.netMwh.add(cell.netMwh());
            }
        }

        // --- Step 2: Forward data on-the-fly from positions (S1) ---
        // Find positions with delivery overlapping the requested range
        List<PositionLedgerEntry> positions = ledgerRepo.findByPortfolioAndDeliveryRange(
            tenantId, portfolioId, rangeStart, rangeEnd);

        // Filter to positions with forward (undelivered) portions
        List<PositionLedgerEntry> forwardPositions = positions.stream()
            .filter(p -> p.deliveryEnd() != null && p.deliveryEnd().isAfter(now))
            .toList();

        // Accumulate forward data by currency
        Map<String, ForwardAccumulator> forwardByCurrency = new LinkedHashMap<>();

        if (!forwardPositions.isEmpty()) {
            // Bulk fetch S6b interval records for forward volume (MW/MWh)
            List<String> tradeLegIds = forwardPositions.stream()
                .map(PositionLedgerEntry::tradeLegId)
                .distinct()
                .toList();

            Map<String, UUID> posIdByTradeLegId = forwardPositions.stream()
                .collect(Collectors.toMap(PositionLedgerEntry::tradeLegId,
                    PositionLedgerEntry::id, (a, b) -> a));

            // Only fetch intervals in the forward portion of the range
            Instant forwardStart = now.isAfter(rangeStart) ? now : rangeStart;
            List<TradeIntervalRecord> forwardIntervals = tradeIntervalCache.getForTradeLegIds(
                tenantId, tradeLegIds, forwardStart, rangeEnd);

            // Group intervals by positionId
            Map<UUID, List<TradeIntervalRecord>> intervalsByPosition = new LinkedHashMap<>();
            for (TradeIntervalRecord r : forwardIntervals) {
                UUID posId = posIdByTradeLegId.get(r.tradeLegId());
                if (posId != null) {
                    intervalsByPosition.computeIfAbsent(posId, k -> new ArrayList<>()).add(r);
                }
            }

            // Per-position: compute forward MtM + aggregate forward volume
            for (PositionLedgerEntry pos : forwardPositions) {
                UUID posId = pos.id();
                List<TradeIntervalRecord> intervals =
                    intervalsByPosition.getOrDefault(posId, List.of());

                // Forward volume aggregation (S6b) — TWA for MW, sum for MWh
                ForwardVolumeAggregation fwdVol = aggregateForwardVolume(intervals);

                // Forward MtM via ForwardMarkService (ADR-002)
                MonthlyMark monthlyMark = null;
                try {
                    monthlyMark = forwardMarkService.computeMonthlyMark(
                        tenantId, posId, forwardStart, rangeEnd);
                } catch (Exception ex) {
                    log.warn("ForwardMarkService.computeMonthlyMark failed for position {}: {}",
                        posId, ex.getMessage());
                }

                String currency = monthlyMark != null ? monthlyMark.currency() : "EUR";
                ForwardAccumulator acc = forwardByCurrency.computeIfAbsent(
                    currency, k -> new ForwardAccumulator());

                if (monthlyMark != null) {
                    acc.unrealizedMtm = acc.unrealizedMtm.add(monthlyMark.forwardMtm());
                }

                // Use S6b intervals for volume if available, else MonthlyMark totalMwh
                if (!intervals.isEmpty()) {
                    acc.netMwh = acc.netMwh.add(fwdVol.netMwh);
                    // TWA accumulation: add weighted MW × minutes
                    long fwdMinutes = intervals.stream()
                        .mapToLong(r -> Duration.between(r.intervalStart(), r.intervalEnd()).toMinutes())
                        .sum();
                    acc.mwWeightedSum = acc.mwWeightedSum.add(
                        fwdVol.netMw.multiply(BigDecimal.valueOf(fwdMinutes)));
                    acc.totalMinutes += fwdMinutes;
                } else if (monthlyMark != null) {
                    acc.netMwh = acc.netMwh.add(monthlyMark.totalMwh());
                }
            }
        }

        // --- Step 3: Merge settled + forward into PortfolioSummary per currency ---
        // Collect all currencies present in either settled or forward
        Map<String, PortfolioSummary> mergedByCurrency = new LinkedHashMap<>();

        for (var entry : settledByCurrency.entrySet()) {
            String currency = entry.getKey();
            SettledAccumulator s = entry.getValue();

            BigDecimal settledNetMw = s.totalMinutes > 0
                ? np.round(s.mwWeightedSum.divide(BigDecimal.valueOf(s.totalMinutes),
                    np.scale(NumericPrecision.Domain.VOLUME), np.roundingMode()),
                    NumericPrecision.Domain.VOLUME)
                : BigDecimal.ZERO;

            BigDecimal rPnl = np.round(s.realizedPnl, NumericPrecision.Domain.MONETARY);

            mergedByCurrency.put(currency, new PortfolioSummary(
                portfolioId, currency, rPnl,
                BigDecimal.ZERO, rPnl,
                settledNetMw, np.round(s.netMwh, NumericPrecision.Domain.ENERGY),
                BigDecimal.ZERO, BigDecimal.ZERO,
                now));
        }

        for (var entry : forwardByCurrency.entrySet()) {
            String currency = entry.getKey();
            ForwardAccumulator f = entry.getValue();

            BigDecimal forwardNetMw = f.totalMinutes > 0
                ? np.round(f.mwWeightedSum.divide(BigDecimal.valueOf(f.totalMinutes),
                    np.scale(NumericPrecision.Domain.VOLUME), np.roundingMode()),
                    NumericPrecision.Domain.VOLUME)
                : BigDecimal.ZERO;

            BigDecimal uMtm = np.round(f.unrealizedMtm, NumericPrecision.Domain.MONETARY);
            BigDecimal fwdMwh = np.round(f.netMwh, NumericPrecision.Domain.ENERGY);

            PortfolioSummary existing = mergedByCurrency.get(currency);
            if (existing != null) {
                // Merge forward into existing settled summary
                BigDecimal totalValue = np.round(
                    existing.realizedPnl().add(uMtm), NumericPrecision.Domain.MONETARY);
                mergedByCurrency.put(currency, new PortfolioSummary(
                    portfolioId, currency,
                    existing.realizedPnl(), uMtm, totalValue,
                    existing.settledNetMw(), existing.settledNetMwh(),
                    forwardNetMw, fwdMwh,
                    now));
            } else {
                // Forward-only currency (no settled data)
                mergedByCurrency.put(currency, new PortfolioSummary(
                    portfolioId, currency,
                    BigDecimal.ZERO, uMtm, uMtm,
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    forwardNetMw, fwdMwh,
                    now));
            }
        }

        if (mergedByCurrency.isEmpty()) {
            return List.of();
        }

        return new ArrayList<>(mergedByCurrency.values());
    }

    /** Mutable accumulator for settled rollup aggregation in portfolioSummaries. */
    private static final class SettledAccumulator {
        BigDecimal realizedPnl = BigDecimal.ZERO;
        BigDecimal mwWeightedSum = BigDecimal.ZERO;
        long totalMinutes = 0L;
        BigDecimal netMwh = BigDecimal.ZERO;
    }

    /** Mutable accumulator for forward on-the-fly aggregation in portfolioSummaries. */
    private static final class ForwardAccumulator {
        BigDecimal unrealizedMtm = BigDecimal.ZERO;
        BigDecimal mwWeightedSum = BigDecimal.ZERO;
        long totalMinutes = 0L;
        BigDecimal netMwh = BigDecimal.ZERO;
    }

    // -------------------------------------------------------------------------
    // L2: Period Grid
    // -------------------------------------------------------------------------

    @Override
    public List<RollupCell> rollupGrid(String tenantId,
                                        String portfolioId,
                                        Instant rangeStart,
                                        Instant rangeEnd,
                                        TimeGranularity granularity) {
        return rollupRepo.findByPortfolio(tenantId, portfolioId, rangeStart, rangeEnd, granularity);
    }

    // -------------------------------------------------------------------------
    // L3: Trade-Level View
    // -------------------------------------------------------------------------

    /**
     * Q-10: Reads pre-materialized trade-leg rollup cells (S7) for the portfolio
     * and computes forward marks on-the-fly only for positions where
     * {@code hasForwardIntervals = true} (M Redis calls where M &lt;&lt; N).
     *
     * <p>Falls back to on-the-fly computation via
     * {@link #positionContributionsOnTheFly} if the rollup table is empty
     * (OI-3: transitional fallback during initial rollout before backfill).
     *
     * <p>Before (on-the-fly): 3 SQL queries + N Redis calls (576K+ rows fetched).
     * After (materialized): 1 SQL query (≤200 rows) + M Redis calls (only PARTIAL/FORWARD).
     *
     * <p>FR-035: MW = TWA, MWh = sum, price = volume-weighted average.
     * D-3: forward marks are not materialized; computed at query time.
     * Pattern #18, S5.3, Appendix B.
     */
    @Override
    public List<PositionContribution> positionContributions(String tenantId,
                                                             String portfolioId,
                                                             Instant periodStart,
                                                             Instant periodEnd) {
        // Step 1: read materialized trade-leg rollup (Q-10)
        List<TradeLegRollupCell> rollups = tradeLegRollupRepo.findByPortfolio(
            tenantId, portfolioId, periodStart, periodEnd, TimeGranularity.MONTHLY);

        if (rollups.isEmpty()) {
            // OI-3: fallback to on-the-fly if rollup table not yet populated
            log.warn("Trade-leg rollup table is empty for tenant={} portfolio={} [{}, {}) — " +
                "falling back to on-the-fly computation. Run backfill to populate the table.",
                tenantId, portfolioId, periodStart, periodEnd);
            return positionContributionsOnTheFly(tenantId, portfolioId, periodStart, periodEnd);
        }

        // Step 1b: resolve current position IDs from live ledger (bitemporal correctness).
        // The rollup table may carry stale position_id values if the supersession cleanup
        // event was not yet processed. One bulk query fetches all current (knownTo IS NULL)
        // positions for this portfolio+range, keyed by tradeLegId.
        List<PositionLedgerEntry> currentPositions =
            ledgerRepo.findByPortfolioAndDeliveryRange(tenantId, portfolioId, periodStart, periodEnd);
        Map<String, PositionLedgerEntry> currentPosByTradeLeg = currentPositions.stream()
            .collect(Collectors.toMap(PositionLedgerEntry::tradeLegId,
                p -> p, (a, b) -> a));  // first occurrence per tradeLegId

        // Step 2: group rollup rows by tradeLegId — the query may return multiple
        // rows per trade leg (one per delivery-month position per MONTHLY bucket,
        // D-1: grain = trade-leg × delivery-month). L3 expects one row per trade leg
        // across the entire requested range.  Aggregate: TWA for MW, sum for MWh/amounts.
        Map<String, List<TradeLegRollupCell>> byTradeLeg = rollups.stream()
            .collect(Collectors.groupingBy(TradeLegRollupCell::tradeLegId, LinkedHashMap::new, Collectors.toList()));

        List<PositionContribution> result = new ArrayList<>(byTradeLeg.size());
        for (var entry : byTradeLeg.entrySet()) {
            List<TradeLegRollupCell> cells = entry.getValue();
            TradeLegRollupCell first = cells.get(0);

            // Aggregate settled values across monthly buckets (FR-035)
            BigDecimal totalSettledMwh = BigDecimal.ZERO;
            BigDecimal totalSettledValue = BigDecimal.ZERO;
            BigDecimal totalMarketValue = BigDecimal.ZERO;
            BigDecimal totalRealizedPnl = BigDecimal.ZERO;
            long totalMinutes = 0;
            BigDecimal weightedMw = BigDecimal.ZERO; // MW × minutes for TWA
            boolean hasForward = false;
            boolean hasSettled = false;
            boolean hasPartial = false;

            for (TradeLegRollupCell cell : cells) {
                totalSettledMwh = totalSettledMwh.add(cell.settledMwh());
                totalSettledValue = totalSettledValue.add(cell.settledValue());
                totalMarketValue = totalMarketValue.add(cell.marketValue());
                totalRealizedPnl = totalRealizedPnl.add(cell.realizedPnl());

                long bucketMinutes = Duration.between(cell.periodStart(), cell.periodEnd()).toMinutes();
                totalMinutes += bucketMinutes;
                weightedMw = weightedMw.add(cell.settledMw().multiply(BigDecimal.valueOf(bucketMinutes)));

                if (cell.hasForwardIntervals()) hasForward = true;
                if ("SETTLED".equals(cell.deliveryStatus())) hasSettled = true;
                else if ("PARTIAL".equals(cell.deliveryStatus())) hasPartial = true;
            }
            // Derive overall status: PARTIAL if buckets mix settled and forward,
            // otherwise use the unanimous bucket status.
            String deliveryStatus;
            if (hasPartial || (hasSettled && hasForward)) {
                deliveryStatus = "PARTIAL";
            } else if (hasForward) {
                deliveryStatus = "FORWARD";
            } else {
                deliveryStatus = "SETTLED";
            }

            // TWA MW = sum(MW × minutes) / totalMinutes
            BigDecimal settledMw = totalMinutes > 0
                ? np.round(weightedMw.divide(BigDecimal.valueOf(totalMinutes),
                    np.scale(NumericPrecision.Domain.VOLUME), np.roundingMode()),
                    NumericPrecision.Domain.VOLUME)
                : BigDecimal.ZERO;

            // Volume-weighted average price = settledValue / settledMwh
            BigDecimal avgPrice = totalSettledMwh.signum() != 0
                ? np.round(totalSettledValue.divide(totalSettledMwh,
                    np.scale(NumericPrecision.Domain.PRICE), np.roundingMode()),
                    NumericPrecision.Domain.PRICE)
                : BigDecimal.ZERO;

            // Resolve the current (knownTo IS NULL) position ID for this trade leg.
            // The rollup may carry a stale position_id if supersession cleanup hasn't run.
            PositionLedgerEntry currentPos = currentPosByTradeLeg.get(first.tradeLegId());
            UUID resolvedPositionId = currentPos != null ? currentPos.id() : first.positionId();

            // Step 3: forward mark (D-3, ADR-002) — only for positions with forward intervals
            BigDecimal forwardMw = BigDecimal.ZERO;
            BigDecimal forwardMwh = BigDecimal.ZERO;
            BigDecimal forwardMarkValue = BigDecimal.ZERO;
            BigDecimal unrealizedMtm = BigDecimal.ZERO;

            if (hasForward) {
                try {
                    MonthlyMark monthlyMark = forwardMarkService.computeMonthlyMark(
                        tenantId, resolvedPositionId, periodStart, periodEnd);
                    if (monthlyMark != null) {
                        forwardMwh = monthlyMark.totalMwh();
                        forwardMarkValue = monthlyMark.forwardMtm();
                        unrealizedMtm = monthlyMark.forwardMtm();
                        // Derive TWA forward MW from totalMwh and full period duration
                        long fwdMinutes = Duration.between(periodStart, periodEnd).toMinutes();
                        if (fwdMinutes > 0 && forwardMwh.signum() != 0) {
                            forwardMw = np.round(
                                forwardMwh.multiply(BigDecimal.valueOf(60))
                                    .divide(BigDecimal.valueOf(fwdMinutes),
                                        np.scale(NumericPrecision.Domain.VOLUME),
                                        np.roundingMode()),
                                NumericPrecision.Domain.VOLUME);
                        }
                    }
                } catch (Exception ex) {
                    log.warn("ForwardMarkService.computeMonthlyMark failed for position {}: {}",
                        resolvedPositionId, ex.getMessage());
                }
            }

            // Use the full requested range as delivery window, not individual bucket boundaries
            result.add(new PositionContribution(
                resolvedPositionId,
                first.tradeId(),
                first.tradeLegId(),
                first.tradeVersion(),
                periodStart,
                periodEnd,
                first.quantity(),
                first.volumeUnit(),
                first.deliveryPointId(),
                deliveryStatus,
                settledMw,
                totalSettledMwh,
                avgPrice,
                np.round(totalSettledValue, NumericPrecision.Domain.MONETARY),
                np.round(totalMarketValue, NumericPrecision.Domain.MONETARY),
                np.round(totalRealizedPnl, NumericPrecision.Domain.MONETARY),
                forwardMw,
                forwardMwh,
                forwardMarkValue,
                unrealizedMtm,
                first.currency()
            ));
        }

        return result;
    }

    /**
     * On-the-fly fallback for {@link #positionContributions} when the rollup table
     * is empty (OI-3: used during initial rollout before backfill completes).
     *
     * <p>Q-2 + Q-9: Hybrid bulk-fetch (§6.5).
     * Step 1: 1 SQL for position metadata.
     * Step 2a: 1 SQL bulk S5a cells (findByPositionIds).
     * Step 2b: 1 SQL bulk S6b records (getForTradeLegIds).
     * Step 2c: ~N Redis lookups via ForwardMarkService.computeMonthlyMark().
     * Step 3: Merge per position; derive deliveryStatus.
     * FR-035: MW = TWA, MWh = sum, price = volume-weighted average.
     */
    private List<PositionContribution> positionContributionsOnTheFly(String tenantId,
                                                                       String portfolioId,
                                                                       Instant periodStart,
                                                                       Instant periodEnd) {
        // Step 1: position metadata
        List<PositionLedgerEntry> positions =
            ledgerRepo.findByPortfolioAndDeliveryRange(
                tenantId, portfolioId, periodStart, periodEnd);
        if (positions.isEmpty()) {
            return List.of();
        }

        List<UUID> positionIds = positions.stream().map(PositionLedgerEntry::id).toList();
        List<String> tradeLegIds = positions.stream()
            .map(PositionLedgerEntry::tradeLegId)
            .distinct()
            .toList();

        // Build lookup: tradeLegId -> positionId (first occurrence)
        Map<String, UUID> posIdByTradeLegId = positions.stream()
            .collect(Collectors.toMap(PositionLedgerEntry::tradeLegId,
                PositionLedgerEntry::id, (a, b) -> a));

        // Step 2a: bulk S5a cells
        List<SettlementCell> allCells = cellRepo.findByPositionIds(
            tenantId, positionIds, periodStart, periodEnd);
        Map<UUID, List<SettlementCell>> cellsByPosition = allCells.stream()
            .collect(Collectors.groupingBy(SettlementCell::positionId,
                LinkedHashMap::new, Collectors.toList()));

        // Step 2b: bulk S6b records
        List<TradeIntervalRecord> allIntervalRecords = tradeIntervalCache.getForTradeLegIds(
            tenantId, tradeLegIds, periodStart, periodEnd);
        // group by positionId via tradeLegId mapping
        Map<UUID, List<TradeIntervalRecord>> intervalsByPosition = new LinkedHashMap<>();
        for (TradeIntervalRecord r : allIntervalRecords) {
            UUID posId = posIdByTradeLegId.get(r.tradeLegId());
            if (posId != null) {
                intervalsByPosition.computeIfAbsent(posId, k -> new ArrayList<>()).add(r);
            }
        }

        // Step 3: group by tradeLegId — D-1 means one position per delivery-month,
        // but L3 expects one row per trade leg across the entire requested range.
        // Aggregate S5a cells and S6b intervals across all positions for the same leg.
        Map<String, List<PositionLedgerEntry>> byTradeLeg = positions.stream()
            .collect(Collectors.groupingBy(PositionLedgerEntry::tradeLegId,
                LinkedHashMap::new, Collectors.toList()));

        List<PositionContribution> result = new ArrayList<>(byTradeLeg.size());
        for (var legEntry : byTradeLeg.entrySet()) {
            List<PositionLedgerEntry> legPositions = legEntry.getValue();
            PositionLedgerEntry firstPos = legPositions.get(0);

            // Collect all S5a cells and S6b intervals across positions for this leg
            List<SettlementCell> allLegCells = new ArrayList<>();
            List<TradeIntervalRecord> allLegIntervals = new ArrayList<>();
            for (PositionLedgerEntry pos : legPositions) {
                allLegCells.addAll(cellsByPosition.getOrDefault(pos.id(), List.of()));
                allLegIntervals.addAll(intervalsByPosition.getOrDefault(pos.id(), List.of()));
            }

            // Aggregate settled (S5a): FR-035
            SettledAggregation settled = aggregateSettled(allLegCells);

            // Aggregate forward volumes (S6b)
            ForwardVolumeAggregation fwd = aggregateForwardVolume(allLegIntervals);

            // ForwardMarkService for forward MtM (ADR-002) — use first position ID
            MonthlyMark monthlyMark = null;
            if (!allLegIntervals.isEmpty()) {
                try {
                    monthlyMark = forwardMarkService.computeMonthlyMark(
                        tenantId, firstPos.id(), periodStart, periodEnd);
                } catch (Exception ex) {
                    log.warn("ForwardMarkService.computeMonthlyMark failed for position {}: {}",
                        firstPos.id(), ex.getMessage());
                }
            }

            BigDecimal forwardMarkValue = monthlyMark != null ? monthlyMark.forwardMtm()
                : BigDecimal.ZERO;

            // Derive deliveryStatus
            boolean hasSettled = !allLegCells.isEmpty();
            boolean hasForward = !allLegIntervals.isEmpty();
            String deliveryStatus;
            if (hasSettled && hasForward) {
                deliveryStatus = "PARTIAL";
            } else if (hasSettled) {
                deliveryStatus = "SETTLED";
            } else {
                deliveryStatus = "FORWARD";
            }

            String currency = allLegCells.isEmpty()
                ? (monthlyMark != null ? monthlyMark.currency() : "EUR")
                : allLegCells.get(0).currency();

            // Sum quantities across positions for the same leg
            BigDecimal totalQuantity = legPositions.stream()
                .map(PositionLedgerEntry::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            result.add(new PositionContribution(
                firstPos.id(),
                firstPos.tradeId(),
                firstPos.tradeLegId(),
                firstPos.tradeVersion(),
                periodStart,
                periodEnd,
                totalQuantity,
                firstPos.volumeUnit() != null ? firstPos.volumeUnit().name() : null,
                firstPos.deliveryPointId(),
                deliveryStatus,
                settled.netMw,
                settled.netMwh,
                settled.avgPrice,
                settled.settledValue,
                settled.marketValue,
                settled.pnl,
                fwd.netMw,
                fwd.netMwh,
                forwardMarkValue,
                forwardMarkValue,  // unrealizedMtm == forwardMarkValue
                currency
            ));
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // L4: Month View
    // -------------------------------------------------------------------------

    /**
     * Q-4: Daily aggregates for a position or portfolio within a month.
     * Prefers DAILY rollup cells; falls back to on-the-fly S5a + ForwardMarkService.
     * Day boundaries computed from the {@code timezone} parameter per S10c.
     */
    @Override
    public List<DailyAggregate> dailyAggregates(String tenantId,
                                                  String portfolioId,
                                                  UUID positionId,
                                                  Instant monthStart,
                                                  Instant monthEnd,
                                                  String timezone) {
        String tz = timezone != null ? timezone : DEFAULT_TIMEZONE;
        ZoneId zone = ZoneId.of(tz);
        Instant now = Instant.now();

        // Try DAILY rollup cells first (S7)
        List<RollupCell> dailyCells = rollupRepo.findByPortfolio(
            tenantId, portfolioId, monthStart, monthEnd, TimeGranularity.DAILY);

        // Build a set of day-start instants covered by rollup cells
        Map<Instant, RollupCell> rollupByDayStart = new LinkedHashMap<>();
        for (RollupCell cell : dailyCells) {
            rollupByDayStart.put(cell.periodStart(), cell);
        }

        // Enumerate CET/CEST days within the month
        List<DailyAggregate> result = new ArrayList<>();
        LocalDate localDay = monthStart.atZone(zone).toLocalDate();
        LocalDate localEnd = monthEnd.atZone(zone).toLocalDate();

        while (!localDay.isAfter(localEnd.minusDays(1))) {
            ZonedDateTime dayStartLocal = localDay.atStartOfDay(zone);
            Instant dayStartUtc = dayStartLocal.toInstant();
            Instant dayEndUtc = dayStartLocal.plusDays(1).toInstant();

            // Only process days that overlap the month window
            if (dayStartUtc.isBefore(monthEnd) && dayEndUtc.isAfter(monthStart)) {
                long durationMinutes = Duration.between(dayStartUtc, dayEndUtc).toMinutes();
                int intervalCount = (int) (durationMinutes / 15);

                boolean isSettled = dayEndUtc.compareTo(now) <= 0;
                boolean isToday = !dayEndUtc.isAfter(now) || !dayStartUtc.isAfter(now);
                String dayStatus = dayEndUtc.compareTo(now) <= 0 ? "SETTLED"
                    : (dayStartUtc.compareTo(now) <= 0 ? "TODAY" : "FORWARD");

                RollupCell rollupCell = rollupByDayStart.get(dayStartUtc);

                if (rollupCell != null) {
                    // Prefer materialized rollup
                    result.add(buildDailyAggregateFromRollup(
                        dayStartUtc, dayEndUtc, dayStatus, intervalCount, rollupCell, isSettled));
                } else {
                    // Fallback: on-the-fly aggregation from S5a / ForwardMarkService
                    result.add(buildDailyAggregateFallback(
                        tenantId, portfolioId, positionId,
                        dayStartUtc, dayEndUtc, dayStatus, intervalCount, isSettled, now));
                }
            }

            localDay = localDay.plusDays(1);
        }

        return result;
    }

    /**
     * Q-4 multi-position: Daily aggregates netted across a position subset.
     * S15.3.1, FR-035. D-13: no Spring types. D-14: tenantId propagated.
     *
     * <p>If {@code positionIds} is null or empty, delegates to the portfolio-scoped
     * single-position-null path (same as calling
     * {@link #dailyAggregates(String, String, UUID, Instant, Instant, String)} with
     * {@code positionId = null}). Otherwise, the same portfolio-scoped logic runs but
     * position fetches are filtered to the requested subset.
     */
    @Override
    public List<DailyAggregate> dailyAggregates(String tenantId,
                                                  String portfolioId,
                                                  List<UUID> positionIds,
                                                  Instant monthStart,
                                                  Instant monthEnd,
                                                  String timezone) {
        if (positionIds == null || positionIds.isEmpty()) {
            // Delegate to portfolio-scoped path (positionId = null)
            return dailyAggregates(tenantId, portfolioId, (UUID) null, monthStart, monthEnd, timezone);
        }

        // Filter: same portfolio-scoped logic but restrict which positions are used
        // in the fallback path. The rollup-cell path (S7) is portfolio-keyed and
        // cannot easily be filtered to a subset without a new query; we therefore
        // go directly to the on-the-fly fallback, filtered to the requested subset.
        // For a single-position subset, we route through the existing single-position path.
        if (positionIds.size() == 1) {
            return dailyAggregates(tenantId, portfolioId, positionIds.get(0), monthStart, monthEnd, timezone);
        }

        // Multi-position: compute on-the-fly for the subset, same day enumeration as
        // the single-position path. We replicate the day-enumeration loop but feed it
        // filtered positions instead of a single positionId.
        String tz = timezone != null ? timezone : DEFAULT_TIMEZONE;
        ZoneId zone = ZoneId.of(tz);
        Instant now = Instant.now();

        // Collect the subset as a Set for O(1) containment checks
        Set<UUID> positionIdSet = new HashSet<>(positionIds);

        List<DailyAggregate> result = new ArrayList<>();
        LocalDate localDay = monthStart.atZone(zone).toLocalDate();
        LocalDate localEnd = monthEnd.atZone(zone).toLocalDate();

        while (!localDay.isAfter(localEnd.minusDays(1))) {
            ZonedDateTime dayStartLocal = localDay.atStartOfDay(zone);
            Instant dayStartUtc = dayStartLocal.toInstant();
            Instant dayEndUtc = dayStartLocal.plusDays(1).toInstant();

            if (dayStartUtc.isBefore(monthEnd) && dayEndUtc.isAfter(monthStart)) {
                long durationMinutes = java.time.Duration.between(dayStartUtc, dayEndUtc).toMinutes();
                int intervalCount = (int) (durationMinutes / 15);

                boolean isSettled = dayEndUtc.compareTo(now) <= 0;
                String dayStatus = dayEndUtc.compareTo(now) <= 0 ? "SETTLED"
                    : (dayStartUtc.compareTo(now) <= 0 ? "TODAY" : "FORWARD");

                result.add(buildDailyAggregateFallbackForSubset(
                    tenantId, portfolioId, positionIdSet,
                    dayStartUtc, dayEndUtc, dayStatus, intervalCount, isSettled, now));
            }

            localDay = localDay.plusDays(1);
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // L4: Settled Day View
    // -------------------------------------------------------------------------

    /**
     * Q-3 + Q-5: Settlement cells for a day, optionally aggregated per FR-035.
     */
    @Override
    public List<SettlementCell> settledDayDetail(String tenantId,
                                                   String portfolioId,
                                                   UUID positionId,
                                                   Instant dayStart,
                                                   Instant dayEnd,
                                                   TimeGranularity subDailyGranularity) {
        List<SettlementCell> cells;
        if (positionId != null) {
            cells = cellRepo.findByPosition(tenantId, positionId, dayStart, dayEnd);
        } else {
            // Portfolio-scoped: find all positions then bulk-fetch
            List<PositionLedgerEntry> positions =
                ledgerRepo.findByPortfolioAndDeliveryRange(
                    tenantId, portfolioId, dayStart, dayEnd);
            if (positions.isEmpty()) {
                return List.of();
            }
            List<UUID> ids = positions.stream().map(PositionLedgerEntry::id).toList();
            cells = cellRepo.findByPositionIds(tenantId, ids, dayStart, dayEnd);
        }

        if (subDailyGranularity == TimeGranularity.MIN_15 || cells.isEmpty()) {
            return cells;
        }

        // Aggregate to MIN_30 or HOURLY
        return aggregateCellsToGranularity(cells, subDailyGranularity);
    }

    /**
     * Q-3 + Q-5 multi-position + contiguous day range. S15.3.2, FR-035.
     * D-13: no Spring types. D-14: tenantId propagated.
     *
     * <p>The {@code [dayStart, dayEnd)} range may span multiple days. The existing
     * overlap query naturally handles this — no special bucketing is needed.
     * {@code positionIds} null or empty → portfolio-scoped.
     */
    @Override
    public List<SettlementCell> settledDayDetail(String tenantId,
                                                   String portfolioId,
                                                   List<UUID> positionIds,
                                                   Instant dayStart,
                                                   Instant dayEnd,
                                                   TimeGranularity subDailyGranularity) {
        List<SettlementCell> cells;

        if (positionIds == null || positionIds.isEmpty()) {
            // Portfolio-scoped: find all positions then bulk-fetch
            List<PositionLedgerEntry> positions =
                ledgerRepo.findByPortfolioAndDeliveryRange(tenantId, portfolioId, dayStart, dayEnd);
            if (positions.isEmpty()) {
                return List.of();
            }
            List<UUID> ids = positions.stream().map(PositionLedgerEntry::id).toList();
            cells = cellRepo.findByPositionIds(tenantId, ids, dayStart, dayEnd);
        } else {
            // Subset of positions: bulk-fetch directly by the given IDs
            cells = cellRepo.findByPositionIds(tenantId, positionIds, dayStart, dayEnd);
        }

        if (subDailyGranularity == TimeGranularity.MIN_15 || cells.isEmpty()) {
            return cells;
        }

        return aggregateCellsToGranularity(cells, subDailyGranularity);
    }

    // -------------------------------------------------------------------------
    // L4: Forward Day View
    // -------------------------------------------------------------------------

    /**
     * Q-6 + Q-7: Forward interval detail, optionally aggregated.
     * Calls ForwardMarkService.computeIntervalMarks() per position (ADR-002).
     */
    @Override
    public List<ForwardIntervalDetail> forwardDayDetail(String tenantId,
                                                         String portfolioId,
                                                         UUID positionId,
                                                         Instant dayStart,
                                                         Instant dayEnd,
                                                         TimeGranularity subDailyGranularity) {
        List<PositionLedgerEntry> positions;
        if (positionId != null) {
            var opt = ledgerRepo.findById(positionId);
            if (opt.isEmpty()) return List.of();
            positions = List.of(opt.get());
        } else {
            positions = ledgerRepo.findByPortfolioAndDeliveryRange(
                tenantId, portfolioId, dayStart, dayEnd);
            if (positions.isEmpty()) return List.of();
        }

        List<ForwardIntervalDetail> result = new ArrayList<>();

        for (PositionLedgerEntry pos : positions) {
            List<IntervalMark> marks;
            try {
                marks = forwardMarkService.computeIntervalMarks(
                    tenantId, pos.id(), dayStart, dayEnd);
            } catch (Exception ex) {
                log.warn("ForwardMarkService.computeIntervalMarks failed for position {}: {}",
                    pos.id(), ex.getMessage());
                continue;
            }

            for (IntervalMark mark : marks) {
                result.add(new ForwardIntervalDetail(
                    mark.intervalStart(),
                    mark.intervalEnd(),
                    mark.positionId(),
                    mark.tradeLegId(),
                    mark.resolvedQty(),
                    mark.resolvedEnergy(),
                    pos.multiplier(),
                    pos.volumeSeriesKey() != null ? pos.volumeSeriesKey().value() : null,
                    mark.evaluatedPrice(),
                    mark.markValue(),
                    mark.curveId(),
                    mark.curveVersion(),
                    mark.currency()
                ));
            }
        }

        if (subDailyGranularity == TimeGranularity.MIN_15 || result.isEmpty()) {
            return result;
        }

        return aggregateForwardIntervalsToGranularity(result, subDailyGranularity);
    }

    /**
     * Q-6 + Q-7 multi-position + contiguous day range. S15.3.2, FR-035.
     * D-13: no Spring types. D-14: tenantId propagated.
     *
     * <p>The {@code [dayStart, dayEnd)} range may span multiple days. Semantics:
     * {@code positionIds} null or empty → portfolio-scoped.
     */
    @Override
    public List<ForwardIntervalDetail> forwardDayDetail(String tenantId,
                                                         String portfolioId,
                                                         List<UUID> positionIds,
                                                         Instant dayStart,
                                                         Instant dayEnd,
                                                         TimeGranularity subDailyGranularity) {
        List<PositionLedgerEntry> positions;

        if (positionIds == null || positionIds.isEmpty()) {
            // Portfolio-scoped
            positions = ledgerRepo.findByPortfolioAndDeliveryRange(tenantId, portfolioId, dayStart, dayEnd);
            if (positions.isEmpty()) return List.of();
        } else {
            // Resolve each requested positionId via ledgerRepo.findById
            positions = new ArrayList<>();
            for (UUID id : positionIds) {
                ledgerRepo.findById(id).ifPresent(positions::add);
            }
            if (positions.isEmpty()) return List.of();
        }

        List<ForwardIntervalDetail> result = new ArrayList<>();

        for (PositionLedgerEntry pos : positions) {
            List<IntervalMark> marks;
            try {
                marks = forwardMarkService.computeIntervalMarks(tenantId, pos.id(), dayStart, dayEnd);
            } catch (Exception ex) {
                log.warn("ForwardMarkService.computeIntervalMarks failed for position {}: {}",
                    pos.id(), ex.getMessage());
                continue;
            }

            for (IntervalMark mark : marks) {
                result.add(new ForwardIntervalDetail(
                    mark.intervalStart(),
                    mark.intervalEnd(),
                    mark.positionId(),
                    mark.tradeLegId(),
                    mark.resolvedQty(),
                    mark.resolvedEnergy(),
                    pos.multiplier(),
                    pos.volumeSeriesKey() != null ? pos.volumeSeriesKey().value() : null,
                    mark.evaluatedPrice(),
                    mark.markValue(),
                    mark.curveId(),
                    mark.curveVersion(),
                    mark.currency()
                ));
            }
        }

        if (subDailyGranularity == TimeGranularity.MIN_15 || result.isEmpty()) {
            return result;
        }

        return aggregateForwardIntervalsToGranularity(result, subDailyGranularity);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /** FR-035: TWA for MW, sum for MWh/amounts, volume-weighted avg for price. */
    private SettledAggregation aggregateSettled(List<SettlementCell> cells) {
        if (cells.isEmpty()) {
            return new SettledAggregation(BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        // Single pass: accumulate MW by interval (for TWA) and sum MWh/amounts
        record IntervalKey(Instant start, Instant end) {}
        Map<IntervalKey, BigDecimal> netMwByInterval = new LinkedHashMap<>();
        BigDecimal netMwh = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalMarket = BigDecimal.ZERO;
        BigDecimal totalPnl = BigDecimal.ZERO;
        for (SettlementCell cell : cells) {
            IntervalKey ik = new IntervalKey(cell.intervalStart(), cell.intervalEnd());
            BigDecimal mw = cell.volumeMw() != null ? cell.volumeMw() : BigDecimal.ZERO;
            netMwByInterval.merge(ik, mw, BigDecimal::add);
            if (cell.volumeMwh() != null) netMwh = netMwh.add(cell.volumeMwh());
            if (cell.amount() != null) totalAmount = totalAmount.add(cell.amount());
            if (cell.marketAmount() != null) totalMarket = totalMarket.add(cell.marketAmount());
            if (cell.pnl() != null) totalPnl = totalPnl.add(cell.pnl());
        }

        // TWA for MW from per-interval net positions
        BigDecimal mwWeightedSum = BigDecimal.ZERO;
        long totalMinutes = 0L;
        for (var e : netMwByInterval.entrySet()) {
            long mins = Duration.between(e.getKey().start(), e.getKey().end()).toMinutes();
            mwWeightedSum = mwWeightedSum.add(
                e.getValue().multiply(BigDecimal.valueOf(mins)));
            totalMinutes += mins;
        }

        BigDecimal netMw = totalMinutes > 0
            ? np.round(mwWeightedSum.divide(BigDecimal.valueOf(totalMinutes),
                np.scale(NumericPrecision.Domain.VOLUME), np.roundingMode()),
                NumericPrecision.Domain.VOLUME)
            : BigDecimal.ZERO;

        netMwh = np.round(netMwh, NumericPrecision.Domain.ENERGY);
        totalAmount = np.round(totalAmount, NumericPrecision.Domain.MONETARY);
        totalMarket = np.round(totalMarket, NumericPrecision.Domain.MONETARY);
        totalPnl = np.round(totalPnl, NumericPrecision.Domain.MONETARY);

        // Volume-weighted average price = totalAmount / totalMwh
        BigDecimal avgPrice = netMwh.signum() != 0
            ? np.round(totalAmount.divide(netMwh,
                np.scale(NumericPrecision.Domain.PRICE), np.roundingMode()),
                NumericPrecision.Domain.PRICE)
            : BigDecimal.ZERO;

        return new SettledAggregation(netMw, netMwh, avgPrice, totalAmount, totalMarket, totalPnl);
    }

    /** FR-035 forward volume aggregation from S6b. TWA for MW, sum for MWh. */
    private ForwardVolumeAggregation aggregateForwardVolume(List<TradeIntervalRecord> records) {
        if (records.isEmpty()) {
            return new ForwardVolumeAggregation(BigDecimal.ZERO, BigDecimal.ZERO);
        }

        BigDecimal mwWeightedSum = BigDecimal.ZERO;
        long totalMinutes = 0L;
        BigDecimal totalMwh = BigDecimal.ZERO;

        for (TradeIntervalRecord r : records) {
            long mins = Duration.between(r.intervalStart(), r.intervalEnd()).toMinutes();
            BigDecimal qty = r.resolvedQty() != null ? r.resolvedQty() : BigDecimal.ZERO;
            mwWeightedSum = mwWeightedSum.add(qty.multiply(BigDecimal.valueOf(mins)));
            totalMinutes += mins;
            if (r.resolvedEnergy() != null) {
                totalMwh = totalMwh.add(r.resolvedEnergy());
            }
        }

        BigDecimal netMw = totalMinutes > 0
            ? np.round(mwWeightedSum.divide(BigDecimal.valueOf(totalMinutes),
                np.scale(NumericPrecision.Domain.VOLUME), np.roundingMode()),
                NumericPrecision.Domain.VOLUME)
            : BigDecimal.ZERO;

        return new ForwardVolumeAggregation(netMw, np.round(totalMwh, NumericPrecision.Domain.ENERGY));
    }

    private DailyAggregate buildDailyAggregateFromRollup(Instant dayStart, Instant dayEnd,
                                                           String dayStatus, int intervalCount,
                                                           RollupCell cell, boolean isSettled) {
        if (isSettled) {
            return new DailyAggregate(
                dayStart, dayEnd, dayStatus, intervalCount,
                cell.netMw(), cell.netMwh(), cell.price(),
                cell.settledValue(), cell.marketValue(), cell.pnl(),
                null, null, null, null,
                cell.currency());
        } else {
            return new DailyAggregate(
                dayStart, dayEnd, dayStatus, intervalCount,
                null, null, null, null, null, null,
                cell.netMw(), cell.netMwh(), cell.marketPrice(),
                cell.forwardMarkValue(),
                cell.currency());
        }
    }

    private DailyAggregate buildDailyAggregateFallback(String tenantId, String portfolioId,
                                                         UUID positionId,
                                                         Instant dayStart, Instant dayEnd,
                                                         String dayStatus, int intervalCount,
                                                         boolean isSettled, Instant now) {
        if (isSettled) {
            // Fetch S5a cells for the day
            List<SettlementCell> dayCells;
            if (positionId != null) {
                dayCells = cellRepo.findByPosition(tenantId, positionId, dayStart, dayEnd);
            } else {
                List<PositionLedgerEntry> dayPositions =
                    ledgerRepo.findByPortfolioAndDeliveryRange(tenantId, portfolioId, dayStart, dayEnd);
                if (dayPositions.isEmpty()) {
                    return emptyDailyAggregate(dayStart, dayEnd, dayStatus, intervalCount, true);
                }
                List<UUID> ids = dayPositions.stream().map(PositionLedgerEntry::id).toList();
                dayCells = cellRepo.findByPositionIds(tenantId, ids, dayStart, dayEnd);
            }

            if (dayCells.isEmpty()) {
                return emptyDailyAggregate(dayStart, dayEnd, dayStatus, intervalCount, true);
            }

            SettledAggregation agg = aggregateSettled(dayCells);
            String currency = dayCells.get(0).currency();

            return new DailyAggregate(
                dayStart, dayEnd, dayStatus, intervalCount,
                agg.netMw, agg.netMwh, agg.avgPrice,
                agg.settledValue, agg.marketValue, agg.pnl,
                null, null, null, null, currency);
        } else {
            // Forward day: call ForwardMarkService
            if (positionId != null) {
                MonthlyMark mark = null;
                try {
                    mark = forwardMarkService.computeMonthlyMark(tenantId, positionId, dayStart, dayEnd);
                } catch (Exception ex) {
                    log.warn("ForwardMarkService failed for pos {} on day {}: {}", positionId, dayStart, ex.getMessage());
                }
                if (mark == null) {
                    return emptyDailyAggregate(dayStart, dayEnd, dayStatus, intervalCount, false);
                }
                return new DailyAggregate(
                    dayStart, dayEnd, dayStatus, intervalCount,
                    null, null, null, null, null, null,
                    null, mark.totalMwh(), mark.avgPrice(), mark.forwardMtm(),
                    mark.currency());
            } else {
                // Portfolio-scoped forward — sum over positions
                List<PositionLedgerEntry> fwdPositions =
                    ledgerRepo.findByPortfolioAndDeliveryRange(tenantId, portfolioId, dayStart, dayEnd);
                if (fwdPositions.isEmpty()) {
                    return emptyDailyAggregate(dayStart, dayEnd, dayStatus, intervalCount, false);
                }
                BigDecimal totalFwdMtm = BigDecimal.ZERO;
                BigDecimal totalFwdMwh = BigDecimal.ZERO;
                String currency = "EUR";
                for (PositionLedgerEntry pos : fwdPositions) {
                    try {
                        MonthlyMark m = forwardMarkService.computeMonthlyMark(
                            tenantId, pos.id(), dayStart, dayEnd);
                        if (m != null) {
                            totalFwdMtm = totalFwdMtm.add(m.forwardMtm());
                            totalFwdMwh = totalFwdMwh.add(m.totalMwh());
                            currency = m.currency();
                        }
                    } catch (Exception ex) {
                        log.warn("ForwardMarkService failed for pos {} on day {}: {}", pos.id(), dayStart, ex.getMessage());
                    }
                }
                BigDecimal curvePrice = totalFwdMwh.signum() != 0
                    ? np.round(totalFwdMtm.divide(totalFwdMwh,
                        np.scale(NumericPrecision.Domain.PRICE), np.roundingMode()),
                        NumericPrecision.Domain.PRICE)
                    : BigDecimal.ZERO;
                return new DailyAggregate(
                    dayStart, dayEnd, dayStatus, intervalCount,
                    null, null, null, null, null, null,
                    null, np.round(totalFwdMwh, NumericPrecision.Domain.ENERGY),
                    curvePrice,
                    np.round(totalFwdMtm, NumericPrecision.Domain.MONETARY),
                    currency);
            }
        }
    }

    /**
     * On-the-fly daily aggregate fallback for a filtered position subset (S15.3.1).
     * Mirrors {@link #buildDailyAggregateFallback} but accepts a set of positionIds
     * rather than a single nullable positionId. FR-035.
     */
    private DailyAggregate buildDailyAggregateFallbackForSubset(String tenantId,
                                                                  String portfolioId,
                                                                  Set<UUID> positionIdSet,
                                                                  Instant dayStart, Instant dayEnd,
                                                                  String dayStatus, int intervalCount,
                                                                  boolean isSettled, Instant now) {
        if (isSettled) {
            // Fetch all positions in range, filter to subset, then bulk-fetch S5a cells
            List<PositionLedgerEntry> dayPositions =
                ledgerRepo.findByPortfolioAndDeliveryRange(tenantId, portfolioId, dayStart, dayEnd);
            List<UUID> ids = dayPositions.stream()
                .map(PositionLedgerEntry::id)
                .filter(positionIdSet::contains)
                .toList();
            if (ids.isEmpty()) {
                return emptyDailyAggregate(dayStart, dayEnd, dayStatus, intervalCount, true);
            }
            List<SettlementCell> dayCells = cellRepo.findByPositionIds(tenantId, ids, dayStart, dayEnd);
            if (dayCells.isEmpty()) {
                return emptyDailyAggregate(dayStart, dayEnd, dayStatus, intervalCount, true);
            }
            SettledAggregation agg = aggregateSettled(dayCells);
            String currency = dayCells.get(0).currency();
            return new DailyAggregate(
                dayStart, dayEnd, dayStatus, intervalCount,
                agg.netMw, agg.netMwh, agg.avgPrice,
                agg.settledValue, agg.marketValue, agg.pnl,
                null, null, null, null, currency);
        } else {
            // Forward day: sum ForwardMarkService results over the subset
            List<PositionLedgerEntry> fwdPositions =
                ledgerRepo.findByPortfolioAndDeliveryRange(tenantId, portfolioId, dayStart, dayEnd);
            List<PositionLedgerEntry> subsetPositions = fwdPositions.stream()
                .filter(p -> positionIdSet.contains(p.id()))
                .toList();
            if (subsetPositions.isEmpty()) {
                return emptyDailyAggregate(dayStart, dayEnd, dayStatus, intervalCount, false);
            }
            BigDecimal totalFwdMtm = BigDecimal.ZERO;
            BigDecimal totalFwdMwh = BigDecimal.ZERO;
            String currency = "EUR";
            for (PositionLedgerEntry pos : subsetPositions) {
                try {
                    MonthlyMark m = forwardMarkService.computeMonthlyMark(
                        tenantId, pos.id(), dayStart, dayEnd);
                    if (m != null) {
                        totalFwdMtm = totalFwdMtm.add(m.forwardMtm());
                        totalFwdMwh = totalFwdMwh.add(m.totalMwh());
                        currency = m.currency();
                    }
                } catch (Exception ex) {
                    log.warn("ForwardMarkService failed for pos {} on day {}: {}",
                        pos.id(), dayStart, ex.getMessage());
                }
            }
            BigDecimal curvePrice = totalFwdMwh.signum() != 0
                ? np.round(totalFwdMtm.divide(totalFwdMwh,
                    np.scale(NumericPrecision.Domain.PRICE), np.roundingMode()),
                    NumericPrecision.Domain.PRICE)
                : BigDecimal.ZERO;
            return new DailyAggregate(
                dayStart, dayEnd, dayStatus, intervalCount,
                null, null, null, null, null, null,
                null, np.round(totalFwdMwh, NumericPrecision.Domain.ENERGY),
                curvePrice,
                np.round(totalFwdMtm, NumericPrecision.Domain.MONETARY),
                currency);
        }
    }

    private DailyAggregate emptyDailyAggregate(Instant dayStart, Instant dayEnd,
                                                 String dayStatus, int intervalCount,
                                                 boolean isSettled) {
        if (isSettled) {
            return new DailyAggregate(dayStart, dayEnd, dayStatus, intervalCount,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, null, null, "EUR");
        } else {
            return new DailyAggregate(dayStart, dayEnd, dayStatus, intervalCount,
                null, null, null, null, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                "EUR");
        }
    }

    /**
     * Aggregate 15-min settlement cells to MIN_30 or HOURLY buckets.
     * FR-035: MW = TWA (weighted by interval duration), MWh = sum,
     * amount/pnl = sum, price = totalAmount / totalMwh (volume-weighted).
     */
    private List<SettlementCell> aggregateCellsToGranularity(List<SettlementCell> cells,
                                                               TimeGranularity granularity) {
        long bucketMinutes = granularity == TimeGranularity.HOURLY ? 60L : 30L;

        // Group by bucket start
        Map<Instant, List<SettlementCell>> byBucket = new LinkedHashMap<>();
        for (SettlementCell cell : cells) {
            long epochMinutes = cell.intervalStart().getEpochSecond() / 60;
            long bucketEpochMinutes = (epochMinutes / bucketMinutes) * bucketMinutes;
            Instant bucketStart = Instant.ofEpochSecond(bucketEpochMinutes * 60);
            byBucket.computeIfAbsent(bucketStart, k -> new ArrayList<>()).add(cell);
        }

        List<SettlementCell> result = new ArrayList<>(byBucket.size());
        for (var entry : byBucket.entrySet()) {
            Instant bucketStart = entry.getKey();
            Instant bucketEnd = bucketStart.plusSeconds(bucketMinutes * 60);
            List<SettlementCell> bucket = entry.getValue();

            BigDecimal mwWeightedSum = BigDecimal.ZERO;
            long totalMins = 0L;
            BigDecimal totalMwh = BigDecimal.ZERO;
            BigDecimal totalAmount = BigDecimal.ZERO;
            BigDecimal totalMarket = BigDecimal.ZERO;
            BigDecimal totalPnl = BigDecimal.ZERO;
            String currency = "EUR";
            String tenantId = bucket.get(0).tenantId();
            UUID positionId = bucket.get(0).positionId();

            for (SettlementCell cell : bucket) {
                long mins = Duration.between(cell.intervalStart(), cell.intervalEnd()).toMinutes();
                BigDecimal mw = cell.volumeMw() != null ? cell.volumeMw() : BigDecimal.ZERO;
                mwWeightedSum = mwWeightedSum.add(mw.multiply(BigDecimal.valueOf(mins)));
                totalMins += mins;
                if (cell.volumeMwh() != null) totalMwh = totalMwh.add(cell.volumeMwh());
                if (cell.amount() != null) totalAmount = totalAmount.add(cell.amount());
                if (cell.marketAmount() != null) totalMarket = totalMarket.add(cell.marketAmount());
                if (cell.pnl() != null) totalPnl = totalPnl.add(cell.pnl());
                currency = cell.currency();
            }

            BigDecimal netMw = totalMins > 0
                ? np.round(mwWeightedSum.divide(BigDecimal.valueOf(totalMins),
                    np.scale(NumericPrecision.Domain.VOLUME), np.roundingMode()),
                    NumericPrecision.Domain.VOLUME)
                : BigDecimal.ZERO;
            BigDecimal netMwh = np.round(totalMwh, NumericPrecision.Domain.ENERGY);
            BigDecimal amount = np.round(totalAmount, NumericPrecision.Domain.MONETARY);
            BigDecimal market = np.round(totalMarket, NumericPrecision.Domain.MONETARY);
            BigDecimal pnl = np.round(totalPnl, NumericPrecision.Domain.MONETARY);

            BigDecimal price = netMwh.signum() != 0
                ? np.round(amount.divide(netMwh,
                    np.scale(NumericPrecision.Domain.PRICE), np.roundingMode()),
                    NumericPrecision.Domain.PRICE)
                : BigDecimal.ZERO;
            BigDecimal marketPrice = netMwh.signum() != 0
                ? np.round(market.divide(netMwh,
                    np.scale(NumericPrecision.Domain.PRICE), np.roundingMode()),
                    NumericPrecision.Domain.PRICE)
                : BigDecimal.ZERO;

            result.add(new SettlementCell(
                UUID.randomUUID(),  // synthetic aggregated cell
                tenantId,
                positionId,
                bucketStart, bucketEnd,
                "AGGREGATED",
                bucket.get(0).cellStatus(),
                price,
                netMw,
                netMwh,
                amount,
                marketPrice,
                market,
                pnl,
                currency,
                java.util.Set.of(),
                java.util.Map.of(),
                Instant.now()
            ));
        }

        return result;
    }

    /**
     * Aggregate ForwardIntervalDetail records to MIN_30 or HOURLY buckets.
     * FR-035: MW = TWA, MWh = sum, markValue = sum,
     * evaluatedPrice = totalMarkValue / totalMwh (volume-weighted).
     */
    private List<ForwardIntervalDetail> aggregateForwardIntervalsToGranularity(
            List<ForwardIntervalDetail> intervals, TimeGranularity granularity) {
        long bucketMinutes = granularity == TimeGranularity.HOURLY ? 60L : 30L;

        Map<Instant, List<ForwardIntervalDetail>> byBucket = new LinkedHashMap<>();
        for (ForwardIntervalDetail detail : intervals) {
            long epochMinutes = detail.intervalStart().getEpochSecond() / 60;
            long bucketEpochMinutes = (epochMinutes / bucketMinutes) * bucketMinutes;
            Instant bucketStart = Instant.ofEpochSecond(bucketEpochMinutes * 60);
            byBucket.computeIfAbsent(bucketStart, k -> new ArrayList<>()).add(detail);
        }

        List<ForwardIntervalDetail> result = new ArrayList<>(byBucket.size());
        for (var entry : byBucket.entrySet()) {
            Instant bucketStart = entry.getKey();
            Instant bucketEnd = bucketStart.plusSeconds(bucketMinutes * 60);
            List<ForwardIntervalDetail> bucket = entry.getValue();

            BigDecimal mwWeightedSum = BigDecimal.ZERO;
            long totalMins = 0L;
            BigDecimal totalMwh = BigDecimal.ZERO;
            BigDecimal totalMarkValue = BigDecimal.ZERO;
            String currency = "EUR";
            String curveId = null;
            long curveVersion = 0L;
            UUID positionId = null;

            for (ForwardIntervalDetail d : bucket) {
                long mins = Duration.between(d.intervalStart(), d.intervalEnd()).toMinutes();
                BigDecimal qty = d.resolvedQty() != null ? d.resolvedQty() : BigDecimal.ZERO;
                mwWeightedSum = mwWeightedSum.add(qty.multiply(BigDecimal.valueOf(mins)));
                totalMins += mins;
                if (d.resolvedEnergy() != null) totalMwh = totalMwh.add(d.resolvedEnergy());
                if (d.markValue() != null) totalMarkValue = totalMarkValue.add(d.markValue());
                currency = d.currency();
                curveId = d.curveId();
                curveVersion = d.curveVersion();
                positionId = d.positionId();
            }

            BigDecimal netMw = totalMins > 0
                ? np.round(mwWeightedSum.divide(BigDecimal.valueOf(totalMins),
                    np.scale(NumericPrecision.Domain.VOLUME), np.roundingMode()),
                    NumericPrecision.Domain.VOLUME)
                : BigDecimal.ZERO;
            BigDecimal netMwh = np.round(totalMwh, NumericPrecision.Domain.ENERGY);
            BigDecimal markVal = np.round(totalMarkValue, NumericPrecision.Domain.MONETARY);

            BigDecimal evalPrice = netMwh.signum() != 0
                ? np.round(markVal.divide(netMwh,
                    np.scale(NumericPrecision.Domain.PRICE), np.roundingMode()),
                    NumericPrecision.Domain.PRICE)
                : BigDecimal.ZERO;

            result.add(new ForwardIntervalDetail(
                bucketStart, bucketEnd,
                positionId, null,  // tradeLegId null for aggregated
                netMw, netMwh,
                BigDecimal.ONE, null,
                evalPrice, markVal,
                curveId, curveVersion, currency
            ));
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Private aggregation result types
    // -------------------------------------------------------------------------

    private record SettledAggregation(
        BigDecimal netMw, BigDecimal netMwh, BigDecimal avgPrice,
        BigDecimal settledValue, BigDecimal marketValue, BigDecimal pnl) {}

    private record ForwardVolumeAggregation(BigDecimal netMw, BigDecimal netMwh) {}
}
