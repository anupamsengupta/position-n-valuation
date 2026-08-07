package com.power.posval.domain.service;

import com.power.posval.domain.event.SettlementComputed;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.SettlementCell;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.Money;
import com.power.posval.domain.model.value.VolumeReference;
import com.power.posval.domain.port.NumericPrecision;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.marketdata.MarketDataPort;
import com.power.posval.domain.port.repository.PriceExpressionRepository;
import com.power.posval.domain.port.repository.SettlementCellRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Settlement materialization job (S5a).
 * Purpose=SETTLEMENT, persists bitemporal cell, publishes SettlementComputed.
 * Pattern #15, FR-056, FR-071, S5a.
 */
public class SettlementMaterializationJob extends AbstractMaterializationJob<SettlementCell> {

    private final SettlementCellRepository cellRepo;
    private final DomainEventPublisher eventPublisher;
    private final NumericPrecision np;

    public SettlementMaterializationJob(VolumeResolver volumeResolver,
                                         PriceEvaluator priceEvaluator,
                                         MarketDataPort marketData,
                                         PriceExpressionRepository priceExpressionRepo,
                                         SettlementCellRepository cellRepo,
                                         DomainEventPublisher eventPublisher,
                                         NumericPrecision np) {
        super(volumeResolver, priceEvaluator, marketData, priceExpressionRepo);
        this.cellRepo = cellRepo;
        this.eventPublisher = eventPublisher;
        this.np = np;
    }

    @Override
    protected List<VolumeRecord> resolveVolume(PositionLedgerEntry position,
                                                DeliveryRange intervalRange) {
        VolumeReference ref = buildVolumeReference(position);
        // Use exact delivery boundaries, not month-aligned DeliveryRange
        return volumeResolver.resolve(ref,
            position.deliveryStart(), position.deliveryEnd(),
            ResolutionPurpose.SETTLEMENT);
    }

    @Override
    protected PriceResolution evaluatePrice(UUID priceExpressionId,
                                             DeliveryPeriod interval) {
        var exprOpt = priceExpressionRepo.findById(priceExpressionId);
        if (exprOpt.isEmpty()) {
            return new PriceResolution(BigDecimal.ZERO, Set.of(), Map.of());
        }
        return priceEvaluator.evaluate(exprOpt.get(), interval,
            ResolutionPurpose.SETTLEMENT, marketData);
    }

    @Override
    protected SettlementCell buildResult(PositionLedgerEntry position,
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

            // Merge active leaves and input version sets from both resolutions
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

    @Override
    protected void flushResults(PositionLedgerEntry position, List<SettlementCell> cells) {
        cellRepo.saveAll(cells);

        Instant eventTime = Instant.now();
        List<Object> events = cells.stream()
            .<Object>map(cell -> new SettlementComputed(
                position.id(),
                ZonedDateTime.ofInstant(cell.intervalStart(),
                    position.deliveryRange().deliveryTimezone()),
                ZonedDateTime.ofInstant(cell.intervalEnd(),
                    position.deliveryRange().deliveryTimezone()),
                new Money(cell.amount(), java.util.Currency.getInstance("EUR")),
                "PROVISIONAL",
                cell.activeLeaves(),
                cell.inputVersionSet(),
                eventTime))
            .toList();

        eventPublisher.publishAll(events);
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
}
