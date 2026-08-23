package com.power.posval.domain.port.cache;

import java.time.Instant;
import java.util.List;

/**
 * Port interface for the trade interval cache (S6b).
 * FR-086: optional, rebuildable materialization.
 * FR-086a: grain = trade_leg_id × atomic interval.
 * FR-086e: commodity-neutral resolved_qty/energy columns.
 */
public interface TradeIntervalCache {

    /** Read pre-multiplied volume for a trade-leg over an interval range. */
    List<TradeIntervalRecord> getForTradeLeg(String tenantId, String tradeLegId,
                                              Instant rangeStart, Instant rangeEnd);

    /**
     * Purge cache entries for a trade-leg within [rangeStart, rangeEnd).
     * FR-086b: triggered by VolumeSuperseded, VolumeReference change, trade amendment.
     */
    void rebuild(String tenantId, String tradeLegId, Instant rangeStart, Instant rangeEnd);

    /** Bulk write pre-multiplied entries. */
    void writeAll(String tenantId, List<TradeIntervalRecord> records);

    /**
     * Q-7: S6b records for multiple trade-legs within an interval range.
     *
     * <p>Avoids N+1 calls to {@link #getForTradeLeg} when L3/L4 queries
     * involve multiple positions. The adapter issues a single SQL query with a
     * {@code trade_leg_id IN (...)} clause. For >100 trade-leg IDs, the adapter
     * batches into chunks of 100 to avoid PostgreSQL parameter limits.
     *
     * <p>Pattern #18 (Repository Port + Adapter), §5.4.
     *
     * @param tenantId    tenant identifier (D-14, Pattern #32)
     * @param tradeLegIds trade-leg identifiers (may be empty — returns empty list)
     * @param rangeStart  interval range start (UTC, inclusive)
     * @param rangeEnd    interval range end (UTC, exclusive)
     * @return all TradeIntervalRecord rows for the given trade-legs in the range
     */
    default List<TradeIntervalRecord> getForTradeLegIds(String tenantId,
                                                         List<String> tradeLegIds,
                                                         Instant rangeStart,
                                                         Instant rangeEnd) {
        throw new UnsupportedOperationException("getForTradeLegIds not implemented");
    }
}
