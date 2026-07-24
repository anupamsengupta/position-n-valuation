package com.power.posval.domain.port.repository;

import com.power.posval.domain.model.VolumeSeries;

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
     * WHERE quality_state IN ('CURRENT', 'EFFECTIVE')
     */
    Optional<VolumeSeries> findCurrentBySeriesKey(String tenantId, String seriesKey);

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
