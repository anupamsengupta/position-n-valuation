package com.power.posval.kafka;

import com.power.posval.domain.event.SettlementRevaluationRequested;
import com.power.posval.domain.event.VolumeSuperseded;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.VolumeReference;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import com.power.posval.domain.service.CacheInvalidationHandler;
import com.power.posval.domain.service.TradeIntervalCacheRebuilder;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

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

        // 2b. S6b: rebuild trade interval cache for each affected position (FR-086b)
        for (PositionLedgerEntry pos : affected) {
            VolumeReference ref = VolumeReference.builder()
                .id(UUID.randomUUID())
                .tradeLegId(pos.tradeLegId())
                .tradeId(pos.tradeId())
                .tenantId(pos.tenantId())
                .multiplier(pos.multiplier())
                .volumeSeriesKey(pos.volumeSeriesKey())
                .effectiveFrom(ZonedDateTime.ofInstant(
                    pos.validFrom(), pos.deliveryRange().deliveryTimezone()))
                .effectiveTo(ZonedDateTime.ofInstant(
                    pos.deliveryRange().endInstant().toInstant(),
                    pos.deliveryRange().deliveryTimezone()))
                .build();
            cacheRebuilder.rebuildForTradeLeg(pos.tenantId(), ref, pos.deliveryRange());
        }

        // 3. Publish revaluation requests
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
