package com.power.posval.domain.exception;

/**
 * Thrown when a domain state machine rejects a transition.
 * Used by {@code QualityState.transitionTo()} and similar guards.
 */
public class IllegalStateTransitionException extends RuntimeException {

    private final String fromState;
    private final String toState;

    public IllegalStateTransitionException(Enum<?> from, Enum<?> to) {
        super("Illegal state transition from %s to %s".formatted(from.name(), to.name()));
        this.fromState = from.name();
        this.toState = to.name();
    }

    public String fromState() { return fromState; }
    public String toState() { return toState; }
}
