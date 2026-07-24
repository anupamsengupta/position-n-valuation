package com.power.posval.domain.model.value;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Links a trade-leg to its volume source. Universal entry point for volume resolution.
 * trade_volume = volume_series_interval.volume × multiplier (D-11, V3.0 §3.3.3).
 * <p>
 * For PPA: multiplier ∈ (0, 1], points to shared FORECAST series.
 * For DA/bilateral: multiplier = 1.0, points to dedicated PROFILE series.
 * Pattern #3, #6, FR-051, D-11, S3.
 */
public record VolumeReference(
    UUID id,
    String tradeLegId,
    String tradeId,
    String assetId,           // nullable — set for asset-linked trades
    BigDecimal multiplier,
    SeriesKey volumeSeriesKey,
    SeriesKey meteredSeriesKey, // nullable — null for exchange/bilateral
    ZonedDateTime effectiveFrom,
    ZonedDateTime effectiveTo
) {
    public VolumeReference {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tradeLegId, "tradeLegId");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(multiplier, "multiplier");
        Objects.requireNonNull(volumeSeriesKey, "volumeSeriesKey");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(effectiveTo, "effectiveTo");
        if (multiplier.compareTo(BigDecimal.ZERO) <= 0 || multiplier.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("multiplier must be in (0, 1]: " + multiplier);
        }
        if (!effectiveTo.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveTo must be after effectiveFrom");
        }
    }

    public static Builder builder() { return new Builder(); }

    /** True if this is a degenerate (fixed-profile) reference. */
    public boolean isFixedProfile() {
        return multiplier.compareTo(BigDecimal.ONE) == 0 && assetId == null;
    }

    public static final class Builder {
        private UUID id;
        private String tradeLegId, tradeId, assetId;
        private BigDecimal multiplier;
        private SeriesKey volumeSeriesKey, meteredSeriesKey;
        private ZonedDateTime effectiveFrom, effectiveTo;

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder tradeLegId(String v) { this.tradeLegId = v; return this; }
        public Builder tradeId(String v) { this.tradeId = v; return this; }
        public Builder assetId(String v) { this.assetId = v; return this; }
        public Builder multiplier(BigDecimal v) { this.multiplier = v; return this; }
        public Builder volumeSeriesKey(SeriesKey v) { this.volumeSeriesKey = v; return this; }
        public Builder meteredSeriesKey(SeriesKey v) { this.meteredSeriesKey = v; return this; }
        public Builder effectiveFrom(ZonedDateTime v) { this.effectiveFrom = v; return this; }
        public Builder effectiveTo(ZonedDateTime v) { this.effectiveTo = v; return this; }

        public VolumeReference build() {
            return new VolumeReference(id, tradeLegId, tradeId, assetId,
                multiplier, volumeSeriesKey, meteredSeriesKey, effectiveFrom, effectiveTo);
        }
    }
}
