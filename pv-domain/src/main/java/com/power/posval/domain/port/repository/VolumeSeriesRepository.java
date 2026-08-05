package com.power.posval.domain.port.repository;

import com.power.posval.domain.model.VolumeSeries;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port interface for volume series persistence.
 * Pattern #18, FR-050, FR-051, S3.
 */
public interface VolumeSeriesRepository {

    void save(VolumeSeries series);

    Optional<VolumeSeries> findById(UUID id);

    /**
     * Find current (non-superseded) series by series key.
     * Loads ALL intervals. Use {@link #findCurrentBySeriesKeyAndRange} for
     * large series to avoid loading hundreds of thousands of intervals.
     */
    Optional<VolumeSeries> findCurrentBySeriesKey(String tenantId, String seriesKey);

    /**
     * Find current series by series key, loading only intervals that overlap
     * the given range [rangeStart, rangeEnd). For a 25-year series at 15-min
     * granularity (~876k intervals), this loads only the ~2,976 intervals for
     * a single month instead of the entire series.
     *
     * <p>Default implementation delegates to {@link #findCurrentBySeriesKey} —
     * the JPA adapter overrides this with a range-filtered SQL query.
     */
    default Optional<VolumeSeries> findCurrentBySeriesKeyAndRange(String tenantId, String seriesKey,
                                                                    Instant rangeStart, Instant rangeEnd) {
        return findCurrentBySeriesKey(tenantId, seriesKey);
    }

    List<VolumeSeries> findByTenantId(String tenantId);

    /**
     * Find series matching a composed specification.
     * Pattern #19: functional-interface specification composable via .and()/.or().
     */
    List<VolumeSeries> findAll(String tenantId, VolumeSeriesSpec spec);

    /** Check existence for idempotent consumer (Pattern #28). */
    boolean existsByTradeIdAndTradeVersion(String tradeId, int tradeVersion);

    /** Supersede a series: mark old version as SUPERSEDED, persist new version. */
    void supersede(VolumeSeries oldVersion, VolumeSeries newVersion);
}
