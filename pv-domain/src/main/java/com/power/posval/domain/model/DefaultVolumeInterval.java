package com.power.posval.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Concrete record implementing VolumeInterval.
 * Pattern #3, FR-050, S3.
 */
public record DefaultVolumeInterval(
    UUID id,
    Instant intervalStart,
    Instant intervalEnd,
    BigDecimal volume,
    BigDecimal energy,
    int version,
    Long supersedesId
) implements VolumeInterval {

    public DefaultVolumeInterval {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(intervalStart, "intervalStart");
        Objects.requireNonNull(intervalEnd, "intervalEnd");
        Objects.requireNonNull(volume, "volume");
        Objects.requireNonNull(energy, "energy");
    }
}
