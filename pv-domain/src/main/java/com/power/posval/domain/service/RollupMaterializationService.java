package com.power.posval.domain.service;

import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.SettlementCell;
import com.power.posval.domain.model.TimeGranularity;
import com.power.posval.domain.port.NumericPrecision;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import com.power.posval.domain.port.repository.RollupCell;
import com.power.posval.domain.port.repository.RollupRepository;
import com.power.posval.domain.port.repository.SettlementCellRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * S7 rollup materialization service.
 * Aggregates settlement cells (S5a) into rollup cells (S7) per
 * (delivery_point, portfolio) × period at specified granularity.
 * FR-090: netMw = time-weighted average, netMwh = sum, settledValue = sum.
 * FR-035: MW replicates on fan-out, averages (TWA) on roll-up;
 *         MWh distributes on fan-out, sums on roll-up.
 */
public class RollupMaterializationService {

    private static final Logger log = LoggerFactory.getLogger(RollupMaterializationService.class);

    private final SettlementCellRepository cellRepo;
    private final PositionLedgerRepository ledgerRepo;
    private final RollupRepository rollupRepo;
    private final NumericPrecision np;

    public RollupMaterializationService(SettlementCellRepository cellRepo,
                                         PositionLedgerRepository ledgerRepo,
                                         RollupRepository rollupRepo,
                                         NumericPrecision np) {
        this.cellRepo = cellRepo;
        this.ledgerRepo = ledgerRepo;
        this.rollupRepo = rollupRepo;
        this.np = np;
    }

    /**
     * Materialize rollup cells for a tenant over [rangeStart, rangeEnd) at MONTHLY granularity.
     * Reads all settlement cells in the range, groups by position, joins with position ledger
     * for portfolio/delivery-point, then aggregates per (deliveryPoint, portfolio) × month.
     */
    public void materialize(String tenantId, Instant rangeStart, Instant rangeEnd) {
        materialize(tenantId, rangeStart, rangeEnd, TimeGranularity.MONTHLY);
    }

    /**
     * Materialize rollup cells at the specified granularity.
     */
    public void materialize(String tenantId, Instant rangeStart, Instant rangeEnd,
                             TimeGranularity granularity) {

        // 1. Load all positions in the delivery range
        List<PositionLedgerEntry> positions =
            ledgerRepo.findAllByDeliveryRange(tenantId, rangeStart, rangeEnd);
        if (positions.isEmpty()) {
            log.debug("No positions for tenant {} in [{}, {})", tenantId, rangeStart, rangeEnd);
            return;
        }

        // Index positions by ID for fast lookup
        Map<UUID, PositionLedgerEntry> positionIndex = positions.stream()
            .collect(Collectors.toMap(PositionLedgerEntry::id, p -> p, (a, b) -> a));

        // 2. Load all settlement cells for each position in the range
        List<SettlementCell> allCells = new ArrayList<>();
        for (PositionLedgerEntry pos : positions) {
            allCells.addAll(cellRepo.findByPosition(tenantId, pos.id(), rangeStart, rangeEnd));
        }

        if (allCells.isEmpty()) {
            log.debug("No settlement cells for tenant {} in [{}, {})", tenantId, rangeStart, rangeEnd);
            return;
        }

        // 3. Group cells by rollup key: (deliveryPointId, portfolioId) × period bucket
        Map<RollupKey, List<SettlementCell>> groups = new LinkedHashMap<>();
        for (SettlementCell cell : allCells) {
            PositionLedgerEntry pos = positionIndex.get(cell.positionId());
            if (pos == null) continue;

            Instant periodStart = truncateToPeriod(cell.intervalStart(), granularity);
            Instant periodEnd = advancePeriod(periodStart, granularity);

            RollupKey key = new RollupKey(
                pos.deliveryPointId() != null ? pos.deliveryPointId() : "DEFAULT",
                pos.portfolioId() != null ? pos.portfolioId() : "DEFAULT",
                periodStart, periodEnd);

            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(cell);
        }

        // 4. Aggregate each group into a RollupCell
        List<RollupCell> rollupCells = new ArrayList<>(groups.size());
        for (var entry : groups.entrySet()) {
            RollupKey key = entry.getKey();
            List<SettlementCell> cells = entry.getValue();

            rollupCells.add(aggregate(key, cells, granularity));
        }

        // 5. Persist
        rollupRepo.saveAll(tenantId, rollupCells);
        log.info("Materialized {} rollup cells for tenant {} in [{}, {}) at {}",
            rollupCells.size(), tenantId, rangeStart, rangeEnd, granularity);
    }

    /**
     * Materialize rollup for a single position (triggered by settlement events).
     */
    public void materializeForPosition(String tenantId, UUID positionId,
                                        Instant rangeStart, Instant rangeEnd) {
        var posOpt = ledgerRepo.findById(positionId);
        if (posOpt.isEmpty()) {
            log.debug("Position {} not found for rollup materialization", positionId);
            return;
        }

        PositionLedgerEntry pos = posOpt.get();
        List<SettlementCell> cells = cellRepo.findByPosition(tenantId, positionId, rangeStart, rangeEnd);
        if (cells.isEmpty()) return;

        // Group by monthly period
        Map<RollupKey, List<SettlementCell>> groups = new LinkedHashMap<>();
        for (SettlementCell cell : cells) {
            Instant periodStart = truncateToPeriod(cell.intervalStart(), TimeGranularity.MONTHLY);
            Instant periodEnd = advancePeriod(periodStart, TimeGranularity.MONTHLY);

            RollupKey key = new RollupKey(
                pos.deliveryPointId() != null ? pos.deliveryPointId() : "DEFAULT",
                pos.portfolioId() != null ? pos.portfolioId() : "DEFAULT",
                periodStart, periodEnd);

            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(cell);
        }

        List<RollupCell> rollupCells = groups.entrySet().stream()
            .map(e -> aggregate(e.getKey(), e.getValue(), TimeGranularity.MONTHLY))
            .toList();

        rollupRepo.saveAll(tenantId, rollupCells);
    }

    private RollupCell aggregate(RollupKey key, List<SettlementCell> cells,
                                  TimeGranularity granularity) {
        // FR-035: MW = time-weighted average; MWh = sum; amounts = sum
        BigDecimal weightedMwSum = BigDecimal.ZERO;
        long totalMinutes = 0;
        BigDecimal totalMwh = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalMarketAmount = BigDecimal.ZERO;
        BigDecimal totalPnl = BigDecimal.ZERO;
        String currency = "EUR";

        for (SettlementCell cell : cells) {
            long minutes = Duration.between(cell.intervalStart(), cell.intervalEnd()).toMinutes();
            if (cell.volumeMw() != null) {
                weightedMwSum = weightedMwSum.add(
                    cell.volumeMw().multiply(BigDecimal.valueOf(minutes)));
            }
            totalMinutes += minutes;

            if (cell.volumeMwh() != null) {
                totalMwh = totalMwh.add(cell.volumeMwh());
            }
            if (cell.amount() != null) {
                totalAmount = totalAmount.add(cell.amount());
            }
            if (cell.marketAmount() != null) {
                totalMarketAmount = totalMarketAmount.add(cell.marketAmount());
            }
            if (cell.pnl() != null) {
                totalPnl = totalPnl.add(cell.pnl());
            }
            currency = cell.currency();
        }

        BigDecimal netMw = totalMinutes > 0
            ? np.round(weightedMwSum.divide(BigDecimal.valueOf(totalMinutes),
                np.scale(NumericPrecision.Domain.VOLUME), np.roundingMode()),
                NumericPrecision.Domain.VOLUME)
            : BigDecimal.ZERO;

        BigDecimal settledValue = np.round(totalAmount, NumericPrecision.Domain.MONETARY);
        BigDecimal marketValue = np.round(totalMarketAmount, NumericPrecision.Domain.MONETARY);
        BigDecimal pnl = np.round(totalPnl, NumericPrecision.Domain.MONETARY);

        // Version hash from cell count + total for staleness detection
        String versionHash = Integer.toHexString(
            Objects.hash(cells.size(), totalMwh, totalAmount));

        return new RollupCell(
            key.periodStart, key.periodEnd, granularity,
            key.deliveryPointId, key.portfolioId,
            false,  // isPeak — requires PeakCalendar (FR-026), not yet implemented
            netMw,
            np.round(totalMwh, NumericPrecision.Domain.ENERGY),
            settledValue,
            marketValue,
            pnl,
            BigDecimal.ZERO,  // forwardMarkValue — populated by ForwardMarkJob path
            currency,
            null,  // calendarVersion — requires MarketCalendar (FR-024)
            versionHash
        );
    }

    private static Instant truncateToPeriod(Instant instant, TimeGranularity granularity) {
        ZonedDateTime zdt = instant.atZone(ZoneOffset.UTC);
        return switch (granularity) {
            case HOURLY -> zdt.truncatedTo(ChronoUnit.HOURS).toInstant();
            case DAILY -> zdt.truncatedTo(ChronoUnit.DAYS).toInstant();
            case MONTHLY -> zdt.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).toInstant();
            default -> zdt.truncatedTo(ChronoUnit.HOURS).toInstant();
        };
    }

    private static Instant advancePeriod(Instant periodStart, TimeGranularity granularity) {
        ZonedDateTime zdt = periodStart.atZone(ZoneOffset.UTC);
        return switch (granularity) {
            case HOURLY -> zdt.plusHours(1).toInstant();
            case DAILY -> zdt.plusDays(1).toInstant();
            case MONTHLY -> zdt.plusMonths(1).toInstant();
            default -> zdt.plusHours(1).toInstant();
        };
    }

    private record RollupKey(String deliveryPointId, String portfolioId,
                              Instant periodStart, Instant periodEnd) {}
}
