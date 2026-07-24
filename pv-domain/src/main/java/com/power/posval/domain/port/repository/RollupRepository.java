package com.power.posval.domain.port.repository;

import com.power.posval.domain.model.TimeGranularity;

import java.time.Instant;
import java.util.List;

/**
 * Port interface for rollup queries (S7).
 * Rollups serve grid requests beyond the hot window and regulatory extracts.
 * FR-090, FR-091.
 */
public interface RollupRepository {

    /** Read rollup cells for a tenant within a delivery range at a given granularity. */
    List<RollupCell> findByRange(String tenantId,
                                  String deliveryPointId,
                                  String portfolioId,
                                  Instant rangeStart,
                                  Instant rangeEnd,
                                  TimeGranularity granularity);

    /**
     * Refresh rollup cells from source data (slot cache + settlement cells).
     * FR-105 step 4: called during batch cycle.
     */
    void refresh(String tenantId,
                  Instant rangeStart,
                  Instant rangeEnd,
                  TimeGranularity granularity);
}
