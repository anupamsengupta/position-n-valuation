package com.power.posval.domain.port.telemetry;

import java.time.Duration;

/**
 * Port interface for metrics emission. §18b.2.4.
 * Allows domain services to signal metric-worthy events without
 * importing Micrometer. Implementation in pv-guice binds to MeterRegistry.
 */
public interface MetricsPort {

    /** Record a timed duration for a named operation. */
    void recordDuration(String metricName, Duration duration, String... tagKeyValuePairs);

    /** Increment a counter. */
    void increment(String metricName, String... tagKeyValuePairs);

    /** Record a distribution value (histogram). */
    void recordValue(String metricName, double value, String... tagKeyValuePairs);
}
