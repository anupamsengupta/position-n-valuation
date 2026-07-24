package com.power.posval.domain.port.telemetry;

/**
 * Port interface for health checks. §18b.5, TR-053.
 * Each implementation checks a single dependency within 2 seconds.
 */
public interface HealthCheck {

    enum Status { UP, DEGRADED, DOWN }

    record Result(Status status, String component, String detail) {}

    /** Check a single dependency. Must complete within 2 seconds. */
    Result check();
}
