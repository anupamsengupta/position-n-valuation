package com.power.posval.kafka;

import com.power.posval.domain.event.VolumePublished;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.value.VolumeReference;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import com.power.posval.domain.service.TradeIntervalCacheRebuilder;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Kafka consumer for {@link VolumePublished} events.
 * FR-052c: S6b initial population — when a volume series is first published,
 * rebuild the trade interval cache for all positions that reference it.
 *
 * <p>No revaluation events are published — this is initial population, not a data change.
 * Pattern #26, FR-052c, S6b.
 */
public class VolumePublishedConsumer extends IdempotentConsumer<VolumePublished> {

    private static final Logger log = LoggerFactory.getLogger(VolumePublishedConsumer.class);

    private final PositionLedgerRepository ledgerRepo;
    private final TradeIntervalCacheRebuilder cacheRebuilder;

    @Inject
    public VolumePublishedConsumer(PositionLedgerRepository ledgerRepo,
                                    TradeIntervalCacheRebuilder cacheRebuilder) {
        this.ledgerRepo = ledgerRepo;
        this.cacheRebuilder = cacheRebuilder;
    }

    @Override
    protected boolean alreadyProcessed(VolumePublished event) {
        // S6b rebuild is idempotent (purge-then-write) — safe to re-process.
        return false;
    }

    @Override
    protected void process(VolumePublished event) {
        // Find positions that reference the published volume series
        var deliveryRange = event.deliveryRange();
        List<PositionLedgerEntry> affected =
            ledgerRepo.findCurrentByVolumeSeriesKeyAndDeliveryRange(
                event.seriesKey().value(),
                deliveryRange.start().toInstant(),
                deliveryRange.end().toInstant());

        log.info("VolumePublished series={} range=[{}, {}): {} affected positions for S6b population",
            event.seriesKey(), deliveryRange.start(), deliveryRange.end(), affected.size());

        // Rebuild S6b trade interval cache for each affected position
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
    }
}
