package com.power.posval.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Single interval within a {@link MeteredActualVolumeSeries}.
 * Pattern #2, #34, S3, V3.0 §3.3.4.
 */
public interface MeteredActualInterval extends Comparable<MeteredActualInterval> {

    UUID id();
    Instant intervalStart();
    Instant intervalEnd();
    BigDecimal volume();
    BigDecimal energy();
    int version();
    Long supersedesId();

    @Override
    default int compareTo(MeteredActualInterval other) {
        return intervalStart().compareTo(other.intervalStart());
    }
}
