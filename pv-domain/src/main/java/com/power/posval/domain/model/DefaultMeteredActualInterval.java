package com.power.posval.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Concrete record implementing MeteredActualInterval.
 * Pattern #3, #34, S3.
 */
public record DefaultMeteredActualInterval(
    UUID id,
    Instant intervalStart,
    Instant intervalEnd,
    BigDecimal volume,
    BigDecimal energy,
    int version,
    Long supersedesId
) implements MeteredActualInterval {

    public DefaultMeteredActualInterval {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(intervalStart, "intervalStart");
        Objects.requireNonNull(intervalEnd, "intervalEnd");
        Objects.requireNonNull(volume, "volume");
        Objects.requireNonNull(energy, "energy");
    }
}
