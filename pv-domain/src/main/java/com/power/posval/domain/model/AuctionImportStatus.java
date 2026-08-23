package com.power.posval.domain.model;

/**
 * State machine for the AuctionImportSession lifecycle.
 * Legal transitions (Pattern #16):
 * <pre>
 * PENDING -> VALIDATING -> VALIDATED -> IMPORTING -> IMPORTED
 *                       -> VALIDATION_FAILED
 *                                     -> IMPORT_FAILED
 * </pre>
 * Pattern #4, #16, S4.2, DA-VOL-01.
 */
public enum AuctionImportStatus {
    PENDING,
    VALIDATING,
    VALIDATED,
    IMPORTING,
    IMPORTED,
    VALIDATION_FAILED,
    IMPORT_FAILED;

    /**
     * Returns true when this status represents a terminal (non-retriable) state.
     */
    public boolean isTerminal() {
        return this == IMPORTED || this == VALIDATION_FAILED || this == IMPORT_FAILED;
    }

    /**
     * Returns true when this status represents a failure outcome.
     */
    public boolean isFailure() {
        return this == VALIDATION_FAILED || this == IMPORT_FAILED;
    }
}
