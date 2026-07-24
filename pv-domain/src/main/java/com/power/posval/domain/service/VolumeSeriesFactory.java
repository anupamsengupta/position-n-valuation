package com.power.posval.domain.service;

import com.power.posval.domain.model.TimeGranularity;
import com.power.posval.domain.model.VolumeInterval;
import com.power.posval.domain.model.VolumeSeries;
import com.power.posval.domain.model.VolumeUnit;
import com.power.posval.domain.model.value.DeliveryPeriod;

import java.util.List;
import java.util.Objects;

/**
 * Factory for creating VolumeSeries with correct ownership routing.
 * D-11: PROFILE (per-trade, multiplier=1.0) vs FORECAST (per-asset, shared).
 * Pattern #7, D-11, FR-050, S3.
 */
public class VolumeSeriesFactory {

    /**
     * Create a PROFILE series for a trade-leg (DA/bilateral).
     * FR-050: per-trade, dedicated, multiplier=1.0.
     * @return new VolumeSeries with seriesType=PROFILE, tradeLegId set, assetId null
     */
    public VolumeSeries createProfile(String tenantId,
                                       String tradeLegId,
                                       String tradeId,
                                       int tradeVersion,
                                       DeliveryPeriod deliveryPeriod,
                                       VolumeUnit volumeUnit,
                                       TimeGranularity granularity,
                                       List<VolumeInterval> intervals) {
        Objects.requireNonNull(tradeLegId, "tradeLegId required for PROFILE");
        // Construct and return — implementation depends on VolumeSeries being a concrete class
        // or a builder. For now this is a skeleton per §9.2.
        throw new UnsupportedOperationException("Full implementation requires concrete VolumeSeries builder");
    }

    /**
     * Create or locate existing FORECAST series for an asset.
     * FR-050: per-asset, shared — one series per (assetId, delivery window).
     * If a current FORECAST series already exists for this asset, return it.
     * @return existing or new VolumeSeries with seriesType=FORECAST
     */
    public VolumeSeries createOrGetForecast(String tenantId,
                                             String assetId,
                                             DeliveryPeriod deliveryPeriod,
                                             VolumeUnit volumeUnit,
                                             TimeGranularity granularity) {
        Objects.requireNonNull(assetId, "assetId required for FORECAST");
        // Lookup existing or construct new — skeleton per §9.2.
        throw new UnsupportedOperationException("Full implementation requires VolumeSeriesRepository dependency");
    }
}
