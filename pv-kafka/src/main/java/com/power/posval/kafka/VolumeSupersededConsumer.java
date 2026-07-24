package com.power.posval.kafka;

import com.power.posval.domain.event.VolumeSuperseded;
import com.power.posval.domain.service.CacheInvalidationHandler;
import jakarta.inject.Inject;

/**
 * Kafka consumer for VolumeSuperseded events.
 * Triggers cache invalidation and S5 re-derivation. Pattern #26.
 */
public class VolumeSupersededConsumer extends IdempotentConsumer<VolumeSuperseded> {

    private final CacheInvalidationHandler cacheInvalidator;

    @Inject
    public VolumeSupersededConsumer(CacheInvalidationHandler cacheInvalidator) {
        this.cacheInvalidator = cacheInvalidator;
    }

    @Override
    protected boolean alreadyProcessed(VolumeSuperseded event) {
        // D-7: re-derive-from-source — idempotent even without dedup check.
        // Version-based: if newVersionId is already reflected in cache, skip.
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
