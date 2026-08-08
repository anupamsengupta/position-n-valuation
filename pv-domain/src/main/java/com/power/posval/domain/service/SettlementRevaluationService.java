package com.power.posval.domain.service;

import com.power.posval.domain.event.SettlementComputed;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.SettlementCell;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.Money;
import com.power.posval.domain.model.value.VolumeReference;
import com.power.posval.domain.port.NumericPrecision;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.marketdata.MarketDataPort;
import com.power.posval.domain.port.repository.PriceExpressionRepository;
import com.power.posval.domain.port.repository.SettlementCellRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Interval-level settlement revaluation service.
 * Replaces settlement cells for a position within a sub-month interval range.
 * Does NOT use AbstractMaterializationJob.execute() (which is final/full-month).
 * Instead, directly uses VolumeResolver + PriceEvaluator to recompute cells.
 *
 * <p>Triggered by MarketDataUpdated and VolumeSuperseded events via
 * SettlementRevaluationRequested. Semantics: delete-then-insert (upsert).
 */
public class SettlementRevaluationService {

    private static final Logger log = LoggerFactory.getLogger(SettlementRevaluationService.class);

    private final VolumeResolver volumeResolver;
    private final PriceEvaluator priceEvaluator;
    private final MarketDataPort marketData;
    private final PriceExpressionRepository priceExpressionRepo;
    private final SettlementCellRepository cellRepo;
    private final DomainEventPublisher eventPublisher;
    private final NumericPrecision np;

    public SettlementRevaluationService(VolumeResolver volumeResolver,
                                         PriceEvaluator priceEvaluator,
                                         MarketDataPort marketData,
                                         PriceExpressionRepository priceExpressionRepo,
                                         SettlementCellRepository cellRepo,
                                         DomainEventPublisher eventPublisher,
                                         NumericPrecision np) {
        this.volumeResolver = volumeResolver;
        this.priceEvaluator = priceEvaluator;
        this.marketData = marketData;
        this.priceExpressionRepo = priceExpressionRepo;
        this.cellRepo = cellRepo;
        this.eventPublisher = eventPublisher;
        this.np = np;
    }

    /**
     * Revalue settlement cells for a position over [intervalStart, intervalEnd).
     * Semantics: delete-then-insert (upsert) for the affected interval range.
     */
    public void revalue(PositionLedgerEntry position,
                        Instant intervalStart,
                        Instant intervalEnd) {

        // Clamp revaluation range to position's actual delivery boundaries
        Instant effectiveStart = max(intervalStart, position.deliveryStart());
        Instant effectiveEnd = min(intervalEnd, position.deliveryEnd());
        if (!effectiveStart.isBefore(effectiveEnd)) {
            log.debug("Revaluation range [{}, {}) does not overlap position {} delivery [{}, {})",
                intervalStart, intervalEnd, position.id(),
                position.deliveryStart(), position.deliveryEnd());
            return;
        }

        // 1. Resolve volume for the sub-range
        VolumeReference ref = buildVolumeReference(position);
        List<VolumeRecord> volumes = volumeResolver.resolve(
            ref, effectiveStart, effectiveEnd, ResolutionPurpose.SETTLEMENT);

        if (volumes.isEmpty()) {
            log.info("No volume records for position {} in [{}, {})",
                position.id(), effectiveStart, effectiveEnd);
            return;
        }

        // 2. Evaluate price and build new cells
        List<SettlementCell> newCells = new ArrayList<>(volumes.size());
        for (VolumeRecord vol : volumes) {
            DeliveryPeriod interval = new DeliveryPeriod(
                ZonedDateTime.ofInstant(vol.intervalStart(),
                    position.deliveryRange().deliveryTimezone()),
                ZonedDateTime.ofInstant(vol.intervalEnd(),
                    position.deliveryRange().deliveryTimezone()),
                position.deliveryRange().deliveryTimezone());

            PriceResolution priceRes = evaluatePrice(
                position.priceExpressionId(), interval);

            newCells.add(buildSettlementCell(position, vol, priceRes));
        }

        // 3. Delete old cells, save new cells (transactional)
        int deleted = cellRepo.deleteByPositionAndInterval(
            position.tenantId(), position.id(), effectiveStart, effectiveEnd);
        log.info("Revaluation: deleted {} old cells for position {} in [{}, {})",
            deleted, position.id(), effectiveStart, effectiveEnd);

        cellRepo.saveAll(newCells);

        // 4. Publish SettlementComputed events
        Instant eventTime = Instant.now();
        List<Object> events = newCells.stream()
            .<Object>map(cell -> new SettlementComputed(
                position.id(),
                ZonedDateTime.ofInstant(cell.intervalStart(),
                    position.deliveryRange().deliveryTimezone()),
                ZonedDateTime.ofInstant(cell.intervalEnd(),
                    position.deliveryRange().deliveryTimezone()),
                new Money(cell.amount(), Currency.getInstance("EUR")),
                "PROVISIONAL",
                cell.activeLeaves(),
                cell.inputVersionSet(),
                eventTime))
            .toList();
        eventPublisher.publishAll(events);

        log.info("Revaluation: saved {} new cells for position {} in [{}, {})",
            newCells.size(), position.id(), effectiveStart, effectiveEnd);
    }

    // --- private helpers (mirror SettlementMaterializationJob logic) ---

    private PriceResolution evaluatePrice(UUID priceExpressionId, DeliveryPeriod interval) {
        var exprOpt = priceExpressionRepo.findById(priceExpressionId);
        if (exprOpt.isEmpty()) {
            return new PriceResolution(BigDecimal.ZERO, Set.of(), Map.of());
        }
        return priceEvaluator.evaluate(exprOpt.get(), interval,
            ResolutionPurpose.SETTLEMENT, marketData);
    }

    private SettlementCell buildSettlementCell(PositionLedgerEntry position,
                                                VolumeRecord volume,
                                                PriceResolution price) {
        BigDecimal tradeAmount = np.round(
            price.value().multiply(volume.energy()), NumericPrecision.Domain.MONETARY);

        BigDecimal marketPrice = null;
        BigDecimal marketAmount = null;
        BigDecimal pnl = null;
        Set<String> activeLeaves = price.activeLeaves();
        Map<String, Long> inputVersionSet = price.inputVersionSet();

        if (position.marketPriceExpressionId() != null) {
            DeliveryPeriod interval = new DeliveryPeriod(
                ZonedDateTime.ofInstant(volume.intervalStart(),
                    position.deliveryRange().deliveryTimezone()),
                ZonedDateTime.ofInstant(volume.intervalEnd(),
                    position.deliveryRange().deliveryTimezone()),
                position.deliveryRange().deliveryTimezone());

            PriceResolution marketRes = evaluatePrice(
                position.marketPriceExpressionId(), interval);

            marketPrice = marketRes.value();
            marketAmount = np.round(
                marketPrice.multiply(volume.energy()), NumericPrecision.Domain.MONETARY);
            pnl = np.round(
                marketAmount.subtract(tradeAmount), NumericPrecision.Domain.MONETARY);

            var mergedLeaves = new HashSet<>(activeLeaves);
            mergedLeaves.addAll(marketRes.activeLeaves());
            activeLeaves = mergedLeaves;

            var mergedVersions = new HashMap<>(inputVersionSet);
            mergedVersions.putAll(marketRes.inputVersionSet());
            inputVersionSet = mergedVersions;
        }

        return new SettlementCell(
            UUID.randomUUID(),
            position.tenantId(),
            position.id(),
            volume.intervalStart(),
            volume.intervalEnd(),
            "SETTLEMENT",
            "PROVISIONAL",
            price.value(),
            volume.volume(),
            volume.energy(),
            tradeAmount,
            marketPrice,
            marketAmount,
            pnl,
            "EUR",
            activeLeaves,
            inputVersionSet,
            Instant.now());
    }

    private VolumeReference buildVolumeReference(PositionLedgerEntry position) {
        return VolumeReference.builder()
            .id(UUID.randomUUID())
            .tradeLegId(position.tradeLegId())
            .tradeId(position.tradeId())
            .tenantId(position.tenantId())
            .multiplier(position.multiplier())
            .volumeSeriesKey(position.volumeSeriesKey())
            .effectiveFrom(ZonedDateTime.ofInstant(
                position.validFrom(), position.deliveryRange().deliveryTimezone()))
            .effectiveTo(ZonedDateTime.ofInstant(
                position.deliveryRange().endInstant().toInstant(),
                position.deliveryRange().deliveryTimezone()))
            .build();
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
