package com.power.posval.domain.service;

import com.power.posval.domain.event.VolumeSuperseded;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.port.cache.VolumeCache;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;

import jakarta.inject.Inject;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * Domain service that handles volume supersession events for cache invalidation.
 * Depends on VolumeCache port, not Redis directly.
 * Pattern #30, FR-052b, S6.
 */
public class CacheInvalidationHandler {

    private final VolumeCache cache;
    private final VolumeSeriesRepository seriesRepo;

    @Inject
    public CacheInvalidationHandler(VolumeCache cache,
                                     VolumeSeriesRepository seriesRepo) {
        this.cache = cache;
        this.seriesRepo = seriesRepo;
    }

    /** Handle VolumeSuperseded — invalidate affected cache entries. */
    public void onVolumeSuperseded(VolumeSuperseded event) {
        var period = event.affectedRange();
        var range = new DeliveryRange(
            YearMonth.from(period.start()),
            YearMonth.from(period.end().minusNanos(1)),
            period.deliveryTimezone());
        cache.invalidate(
            event.seriesKey().value(),
            event.seriesKey().value(),
            range);
    }

    /** Handle VolumeReference change — invalidate both old and new series. */
    public void onVolumeReferenceChanged(String tenantId,
                                          String oldSeriesKey,
                                          String newSeriesKey) {
        cache.invalidateAll(tenantId, oldSeriesKey);
        cache.invalidateAll(tenantId, newSeriesKey);
    }
}
