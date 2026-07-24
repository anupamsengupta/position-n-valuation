package com.power.posval.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Single time-sliced interval within a {@link VolumeSeries}.
 * Ordered by {@code intervalStart} for {@code SequencedSet} usage.
 * Pattern #2, FR-050, D-11, S3, V3.0 §3.3.1.
 */
public interface VolumeInterval extends Comparable<VolumeInterval> {

    UUID id();
    Instant intervalStart();
    Instant intervalEnd();
    BigDecimal volume();
    BigDecimal energy();
    int version();
    Long supersedesId(); // nullable

    @Override
    default int compareTo(VolumeInterval other) {
        return intervalStart().compareTo(other.intervalStart());
    }
}
