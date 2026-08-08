package com.power.posval.kafka;

import com.power.posval.domain.event.MarketDataUpdated;
import com.power.posval.domain.event.SettlementRevaluationRequested;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.port.cache.MarketDataCache;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Kafka consumer for {@link MarketDataUpdated} events.
 * Invalidates Redis market data cache on data changes, then triggers
 * settlement revaluation for affected positions.
 * Pattern #26, #30.
 */
public class MarketDataUpdatedConsumer extends IdempotentConsumer<MarketDataUpdated> {

    private final MarketDataCache cache;
    private final PositionLedgerRepository ledgerRepo;
    private final DomainEventPublisher eventPublisher;

    @Inject
    public MarketDataUpdatedConsumer(MarketDataCache cache,
                                      PositionLedgerRepository ledgerRepo,
                                      DomainEventPublisher eventPublisher) {
        this.cache = cache;
        this.ledgerRepo = ledgerRepo;
        this.eventPublisher = eventPublisher;
    }

    @Override
    protected boolean alreadyProcessed(MarketDataUpdated event) {
        // Cache invalidation is idempotent — re-invalidation is a no-op.
        return false;
    }

    @Override
    protected void process(MarketDataUpdated event) {
        // 1. Cache invalidation (existing logic)
        if (event.affectedRangeStart() != null && event.affectedRangeEnd() != null) {
            cache.invalidate(event.tenantId(), event.dataType(), event.series(),
                event.affectedRangeStart(), event.affectedRangeEnd());
        } else {
            cache.invalidate(event.tenantId(), event.dataType(), event.series());
        }

        // 2. Find affected positions and publish revaluation requests
        if (event.affectedRangeStart() != null && event.affectedRangeEnd() != null) {
            List<PositionLedgerEntry> affected = ledgerRepo.findAllByDeliveryRange(
                event.tenantId(), event.affectedRangeStart(), event.affectedRangeEnd());

            for (PositionLedgerEntry pos : affected) {
                eventPublisher.publish(new SettlementRevaluationRequested(
                    pos.tenantId(),
                    pos.id(),
                    event.affectedRangeStart(),
                    event.affectedRangeEnd(),
                    "MARKET_DATA",
                    event.eventTime()));
            }
        }
    }
}
