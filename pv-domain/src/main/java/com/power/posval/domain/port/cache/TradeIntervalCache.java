package com.power.posval.domain.port.cache;

import com.power.posval.domain.model.value.DeliveryRange;

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
     * Rebuild cache entries for affected trade-leg × interval range.
     * FR-086b: triggered by VolumeSuperseded, VolumeReference change, trade amendment.
     */
    void rebuild(String tenantId, String tradeLegId, DeliveryRange affectedRange);

    /** Bulk write pre-multiplied entries. */
    void writeAll(String tenantId, List<TradeIntervalRecord> records);
}
