package com.power.posval.domain.port.repository;

import com.power.posval.domain.model.MeteredActualVolumeSeries;
import com.power.posval.domain.model.value.SeriesKey;

import java.util.Optional;

/**
 * Port interface for metered actual volume series persistence.
 * Pattern #18, S3.
 */
public interface MeteredActualRepository {

    /** Find current metered actual series by series key. */
    Optional<MeteredActualVolumeSeries> findCurrentBySeriesKey(String tenantId, SeriesKey seriesKey);
}
