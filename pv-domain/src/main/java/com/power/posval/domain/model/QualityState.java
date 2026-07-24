package com.power.posval.domain.model;

import com.power.posval.domain.exception.IllegalStateTransitionException;

/**
 * Quality progression state per volume layer.
 * Transition guards are methods — no framework dependency.
 * Pattern #4, #16, FR-054, V3.0 §3.2.6.
 */
public enum QualityState {
    // PROFILE series
    EFFECTIVE,
    AMENDED,
    // FORECAST series
    CURRENT,
    SUPERSEDED,
    // METERED_ACTUAL series
    PROVISIONAL,
    VALIDATED,
    ESTIMATED;

    /**
     * Guard: returns true if transition from this state to target is allowed.
     * PROFILE: EFFECTIVE → AMENDED
     * FORECAST: CURRENT → SUPERSEDED
     * METERED_ACTUAL: PROVISIONAL → VALIDATED, PROVISIONAL → ESTIMATED,
     *                 ESTIMATED → VALIDATED
     */
    public boolean canTransitionTo(QualityState target) {
        return switch (this) {
            case EFFECTIVE   -> target == AMENDED;
            case AMENDED     -> false;
            case CURRENT     -> target == SUPERSEDED;
            case SUPERSEDED  -> false;
            case PROVISIONAL -> target == VALIDATED || target == ESTIMATED;
            case VALIDATED   -> false;
            case ESTIMATED   -> target == VALIDATED;
        };
    }

    /**
     * Performs transition or throws. Pure domain logic.
     * @throws IllegalStateTransitionException if transition is not allowed.
     */
    public QualityState transitionTo(QualityState target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateTransitionException(this, target);
        }
        return target;
    }

    /** True if this state is applicable to PROFILE series. */
    public boolean isProfileState() { return this == EFFECTIVE || this == AMENDED; }

    /** True if this state is applicable to FORECAST series. */
    public boolean isForecastState() { return this == CURRENT || this == SUPERSEDED; }

    /** True if this state is applicable to METERED_ACTUAL series. */
    public boolean isMeteredState() {
        return this == PROVISIONAL || this == VALIDATED || this == ESTIMATED;
    }
}
