package com.power.posval.redis;

import java.time.Duration;

/**
 * TTL configuration for Redis caches. Passed via constructor — no framework dependency.
 * Defaults match production-reasonable values for EU power market data.
 */
public record RedisCacheTtl(
    Duration marketDataStable,
    Duration marketDataVolatile,
    Duration volume,
    Duration forwardMark,
    int scanBatchSize
) {
    public static final RedisCacheTtl DEFAULTS = new RedisCacheTtl(
        Duration.ofHours(24),   // fixings, FX, index, spread
        Duration.ofHours(1),    // forward curves, vol surfaces
        Duration.ofHours(24),   // volume intervals
        Duration.ofMinutes(5),  // ephemeral forward marks
        1000                    // SCAN COUNT per iteration
    );

    public long marketDataStableSeconds() { return marketDataStable.toSeconds(); }
    public long marketDataVolatileSeconds() { return marketDataVolatile.toSeconds(); }
    public long volumeSeconds() { return volume.toSeconds(); }
    public long forwardMarkSeconds() { return forwardMark.toSeconds(); }
}
