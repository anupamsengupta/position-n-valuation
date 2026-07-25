package com.power.posval.kafka;

import com.power.posval.domain.event.VolumeSuperseded;
import com.power.posval.domain.service.CacheInvalidationHandler;
import com.power.posval.domain.service.TradeIntervalCacheRebuilder;
import jakarta.inject.Inject;

/**
 * Kafka consumer for VolumeSuperseded events.
 * Triggers cache invalidation and S6b rebuild. Pattern #26.
 */
public class VolumeSupersededConsumer extends IdempotentConsumer<VolumeSuperseded> {

    private final CacheInvalidationHandler cacheInvalidator;
    private final TradeIntervalCacheRebuilder cacheRebuilder;

    @Inject
    public VolumeSupersededConsumer(CacheInvalidationHandler cacheInvalidator,
                                     TradeIntervalCacheRebuilder cacheRebuilder) {
        this.cacheInvalidator = cacheInvalidator;
        this.cacheRebuilder = cacheRebuilder;
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

        // 2. S5 re-derivation for affected cells is triggered downstream
        //    via the dependency index (S8) — the invalidation handler
        //    publishes follow-up events that settlement consumers pick up.
    }
}
