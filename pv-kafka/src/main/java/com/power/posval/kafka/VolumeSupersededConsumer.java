package com.power.posval.kafka;

import com.power.posval.domain.event.SettlementRevaluationRequested;
import com.power.posval.domain.event.VolumeSuperseded;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import com.power.posval.domain.service.CacheInvalidationHandler;
import com.power.posval.domain.service.TradeIntervalCacheRebuilder;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;

/**
 * Kafka consumer for VolumeSuperseded events.
 * Triggers cache invalidation, then publishes settlement revaluation
 * requests for positions that reference the affected volume series.
 * Pattern #26.
 */
public class VolumeSupersededConsumer extends IdempotentConsumer<VolumeSuperseded> {

    private final CacheInvalidationHandler cacheInvalidator;
    private final TradeIntervalCacheRebuilder cacheRebuilder;
    private final PositionLedgerRepository ledgerRepo;
    private final DomainEventPublisher eventPublisher;

    @Inject
    public VolumeSupersededConsumer(CacheInvalidationHandler cacheInvalidator,
                                     TradeIntervalCacheRebuilder cacheRebuilder,
                                     PositionLedgerRepository ledgerRepo,
                                     DomainEventPublisher eventPublisher) {
        this.cacheInvalidator = cacheInvalidator;
        this.cacheRebuilder = cacheRebuilder;
        this.ledgerRepo = ledgerRepo;
        this.eventPublisher = eventPublisher;
    }

    @Override
    protected boolean alreadyProcessed(VolumeSuperseded event) {
        // D-7: re-derive-from-source — idempotent even without dedup check.
        return false;
    }

    @Override
    protected void process(VolumeSuperseded event) {
        // 1. Invalidate volume cache for affected series/range
        cacheInvalidator.onVolumeSuperseded(event);

        // 2. Find affected positions by series key + delivery range overlap
        DeliveryPeriod affectedRange = event.affectedRange();
        Instant rangeStart = affectedRange.start().toInstant();
        Instant rangeEnd = affectedRange.end().toInstant();

        List<PositionLedgerEntry> affected =
            ledgerRepo.findCurrentByVolumeSeriesKeyAndDeliveryRange(
                event.seriesKey().value(), rangeStart, rangeEnd);

        for (PositionLedgerEntry pos : affected) {
            eventPublisher.publish(new SettlementRevaluationRequested(
                pos.tenantId(),
                pos.id(),
                rangeStart,
                rangeEnd,
                "VOLUME_SUPERSEDED",
                event.eventTime()));
        }
    }
}
