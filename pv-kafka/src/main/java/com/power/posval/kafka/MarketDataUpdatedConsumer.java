package com.power.posval.kafka;

import com.power.posval.domain.event.MarketDataUpdated;
import com.power.posval.domain.port.cache.MarketDataCache;
import jakarta.inject.Inject;

/**
 * Kafka consumer for {@link MarketDataUpdated} events.
 * Invalidates Redis market data cache on data changes.
 * Pattern #26, #30.
 */
public class MarketDataUpdatedConsumer extends IdempotentConsumer<MarketDataUpdated> {

    private final MarketDataCache cache;

    @Inject
    public MarketDataUpdatedConsumer(MarketDataCache cache) {
        this.cache = cache;
    }

    @Override
    protected boolean alreadyProcessed(MarketDataUpdated event) {
        // Cache invalidation is idempotent — re-invalidation is a no-op.
        return false;
    }

    @Override
    protected void process(MarketDataUpdated event) {
        if (event.affectedRangeStart() != null && event.affectedRangeEnd() != null) {
            cache.invalidate(event.tenantId(), event.dataType(), event.series(),
                event.affectedRangeStart(), event.affectedRangeEnd());
        } else {
            cache.invalidate(event.tenantId(), event.dataType(), event.series());
        }
    }
}
