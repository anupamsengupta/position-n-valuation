package com.power.posval.domain.model;

/**
 * Severity level of an OperationalAlert. Drives UI display priority.
 * Pattern #4, S4.2, DA-OPS-01.
 */
public enum AlertSeverity {
    /** Immediate action required; blocking operational flow. */
    CRITICAL,
    /** Degraded operation; attention required but flow continues. */
    WARNING,
    /** Informational only; no action required. */
    INFO
}
