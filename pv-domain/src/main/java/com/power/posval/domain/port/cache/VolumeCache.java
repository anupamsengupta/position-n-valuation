package com.power.posval.domain.port.cache;

import com.power.posval.domain.model.value.DeliveryRange;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Port interface for the volume/position cache layer (S6).
 * Pattern #29 (read-through), #30 (event invalidation), #31 (pipeline batching).
 * FR-079–FR-085.
 */
public interface VolumeCache {

    /**
     * Get a single cached interval value.
     * Pattern #29: on miss, reads from DB, populates cache.
     * @param readConsistent if true, bypasses cache and reads from writer endpoint
     */
    Optional<CachedInterval> get(String tenantId, String seriesKey,
                                  Instant intervalStart, boolean readConsistent);

    /**
     * Bulk get for a series across multiple interval starts.
     * Pattern #31: translates to Redis MGET for up to 2,976 keys per delivery month.
     */
    List<CachedInterval> getAll(String tenantId, String seriesKey,
                                 List<Instant> intervalStarts);

    /** Write a cached interval. TTL: 24 hours. */
    void put(String tenantId, String seriesKey,
             Instant intervalStart, CachedInterval value);

    /** Bulk write. Redis MULTI-EXEC for atomic batch writes. */
    void putAll(String tenantId, String seriesKey,
                Map<Instant, CachedInterval> values);

    /**
     * Invalidate cached entries for a series within an affected range.
     * Pattern #30: triggered by VolumeSuperseded event.
     */
    void invalidate(String tenantId, String seriesKey,
                     DeliveryRange affectedRange);

    /** Invalidate all entries for a series (trade amendment, full supersession). */
    void invalidateAll(String tenantId, String seriesKey);
}
