package com.power.posval.domain.model;

/**
 * Lifecycle status of an OperationalAlert (Pattern #16 state machine).
 * Legal transitions:
 * <pre>
 * OPEN -> ACKNOWLEDGED -> RESOLVED
 * OPEN ->               -> RESOLVED
 * </pre>
 * Pattern #4, #16, S4.2, DA-OPS-01.
 */
public enum AlertStatus {
    /** Alert raised; not yet acted upon. */
    OPEN,
    /** Acknowledged by a user; root cause investigation in progress. */
    ACKNOWLEDGED,
    /** Alert condition cleared; no further action required. */
    RESOLVED;

    /**
     * Returns true when this status represents a terminal state.
     */
    public boolean isTerminal() {
        return this == RESOLVED;
    }
}
