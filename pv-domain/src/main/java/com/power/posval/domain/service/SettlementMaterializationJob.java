package com.power.posval.domain.service;

import com.power.posval.domain.event.SettlementComputed;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.Money;
import com.power.posval.domain.model.value.VolumeReference;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.marketdata.MarketDataPort;
import com.power.posval.domain.port.repository.SettlementCellRepository;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
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
                                         SettlementCellRepository cellRepo,
                                         DomainEventPublisher eventPublisher) {
        super(volumeResolver, priceEvaluator, marketData);
        this.cellRepo = cellRepo;
        this.eventPublisher = eventPublisher;
    }

    @Override
    protected List<VolumeRecord> resolveVolume(PositionLedgerEntry position,
                                                DeliveryRange intervalRange) {
        // FR-051a: purpose=SETTLEMENT reads metered actuals for asset-linked trades
        // Simplified — full implementation requires VolumeReference lookup
        return List.of();
    }

    @Override
    protected PriceResolution evaluatePrice(UUID priceExpressionId,
                                             DeliveryPeriod interval) {
        // FR-048e: purpose=SETTLEMENT uses settlement series on MarketDataLeaf
        // Simplified — full implementation requires PriceExpression loading
        return null;
    }

    @Override
    protected void writeResult(PositionLedgerEntry position,
                                VolumeRecord volume,
                                PriceResolution price) {
        // FR-071: persist with active_leaves and input_version_set
        cellRepo.save(null); // Skeleton — real implementation builds SettlementCell

        eventPublisher.publish(new SettlementComputed(
            position.id(),
            ZonedDateTime.ofInstant(volume.intervalStart(),
                position.deliveryRange().deliveryTimezone()),
            ZonedDateTime.ofInstant(volume.intervalEnd(),
                position.deliveryRange().deliveryTimezone()),
            new Money(price.value(), java.util.Currency.getInstance("EUR")),
            "PROVISIONAL",
            price.activeLeaves(),
            price.inputVersionSet(),
            Instant.now()));
    }
}
