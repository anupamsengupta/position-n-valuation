package com.power.posval.kafka;

import com.power.posval.domain.event.MarketDataUpdated;
import com.power.posval.domain.event.SettlementRevaluationRequested;
import com.power.posval.domain.port.cache.MarketDataCache;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.repository.DependencyIndex;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * Kafka consumer for {@link MarketDataUpdated} events.
 * Invalidates Redis market data cache on data changes, then triggers
 * settlement revaluation for affected positions using the S8 dependency
 * index (FR-103 blast-radius optimization).
 *
 * <p>Uses {@link DependencyIndex#findAffectedPositionIds} to identify only
 * positions whose price expressions actually reference the changed series,
 * rather than brute-force scanning all positions in the delivery range.
 *
 * Pattern #26, #30, FR-103.
 */
public class MarketDataUpdatedConsumer extends IdempotentConsumer<MarketDataUpdated> {

    private static final Logger log = LoggerFactory.getLogger(MarketDataUpdatedConsumer.class);

    private final MarketDataCache cache;
    private final DependencyIndex dependencyIndex;
    private final DomainEventPublisher eventPublisher;

    @Inject
    public MarketDataUpdatedConsumer(MarketDataCache cache,
                                      DependencyIndex dependencyIndex,
                                      DomainEventPublisher eventPublisher) {
        this.cache = cache;
        this.dependencyIndex = dependencyIndex;
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

        // 2. Use S8 dependency index to find only positions whose price expressions
        //    reference the changed series (FR-103 blast-radius optimization)
        if (event.affectedRangeStart() != null && event.affectedRangeEnd() != null) {
            List<UUID> affectedPositionIds = dependencyIndex.findAffectedPositionIds(
                event.tenantId(), event.series(),
                event.affectedRangeStart(), event.affectedRangeEnd());

            log.info("MarketDataUpdated series={} range=[{}, {}): {} affected positions via S8 index",
                event.series(), event.affectedRangeStart(), event.affectedRangeEnd(),
                affectedPositionIds.size());

            for (UUID positionId : affectedPositionIds) {
                eventPublisher.publish(new SettlementRevaluationRequested(
                    event.tenantId(),
                    positionId,
                    event.affectedRangeStart(),
                    event.affectedRangeEnd(),
                    "MARKET_DATA",
                    event.eventTime()));
            }
        }
    }
}
