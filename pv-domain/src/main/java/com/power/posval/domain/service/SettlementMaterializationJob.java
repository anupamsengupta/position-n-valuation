package com.power.posval.domain.service;

import com.power.posval.domain.event.SettlementComputed;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.SettlementCell;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.Money;
import com.power.posval.domain.model.value.VolumeReference;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.marketdata.MarketDataPort;
import com.power.posval.domain.port.repository.PriceExpressionRepository;
import com.power.posval.domain.port.repository.SettlementCellRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Settlement materialization job (S5a).
 * Purpose=SETTLEMENT, persists bitemporal cell, publishes SettlementComputed.
 * Pattern #15, FR-056, FR-071, S5a.
 */
public class SettlementMaterializationJob extends AbstractMaterializationJob {

    private final SettlementCellRepository cellRepo;
    private final DomainEventPublisher eventPublisher;

    public SettlementMaterializationJob(VolumeResolver volumeResolver,
                                         PriceEvaluator priceEvaluator,
                                         MarketDataPort marketData,
                                         PriceExpressionRepository priceExpressionRepo,
                                         SettlementCellRepository cellRepo,
                                         DomainEventPublisher eventPublisher) {
        super(volumeResolver, priceEvaluator, marketData, priceExpressionRepo);
        this.cellRepo = cellRepo;
        this.eventPublisher = eventPublisher;
    }

    @Override
    protected List<VolumeRecord> resolveVolume(PositionLedgerEntry position,
                                                DeliveryRange intervalRange) {
        VolumeReference ref = buildVolumeReference(position);
        return volumeResolver.resolve(ref, intervalRange, ResolutionPurpose.SETTLEMENT);
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
    protected void writeResult(PositionLedgerEntry position,
                                VolumeRecord volume,
                                PriceResolution price) {
        BigDecimal amount = price.value().multiply(volume.energy());// TO DO does it not require scale handling ?
        Instant now = Instant.now();

        SettlementCell cell = new SettlementCell(
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
            amount,
            "EUR",
            price.activeLeaves(),
            price.inputVersionSet(),
            now, null, now, null);

        cellRepo.save(cell);

        eventPublisher.publish(new SettlementComputed(
            position.id(),
            ZonedDateTime.ofInstant(volume.intervalStart(),
                position.deliveryRange().deliveryTimezone()),
            ZonedDateTime.ofInstant(volume.intervalEnd(),
                position.deliveryRange().deliveryTimezone()),
            new Money(amount, java.util.Currency.getInstance("EUR")),
            "PROVISIONAL",
            price.activeLeaves(),
            price.inputVersionSet(),
            now));
    }

    private VolumeReference buildVolumeReference(PositionLedgerEntry position) {
        return VolumeReference.builder()
            .id(UUID.randomUUID())
            .tradeLegId(position.tradeLegId())
            .tradeId(position.tradeId())
            .multiplier(BigDecimal.ONE) //TO DO - whi sis this 1
            .volumeSeriesKey(position.volumeSeriesKey())
            .effectiveFrom(ZonedDateTime.ofInstant(
                position.validFrom(), position.deliveryRange().deliveryTimezone()))
            .effectiveTo(ZonedDateTime.ofInstant(
                position.deliveryRange().endInstant().toInstant(),
                position.deliveryRange().deliveryTimezone()))
            .build();
    }
}
