package com.power.posval.domain.model.value;

import java.util.Objects;

/**
 * Stable external key for a volume series. Survives amendments.
 * Examples: "FCST-WP-NORDSEE", "VS-T5500-1", "MTR-WP-NORDSEE".
 * Pattern #3, #8, FR-054, S3.
 */
public record SeriesKey(String value) {
    public SeriesKey {
        Objects.requireNonNull(value, "seriesKey");
        if (value.isBlank()) {
            throw new IllegalArgumentException("seriesKey must not be blank");
        }
    }

    public static SeriesKey of(String prefix, String id) {
        return new SeriesKey(prefix + "-" + id);
    }

    @Override
    public String toString() { return value; }
}
