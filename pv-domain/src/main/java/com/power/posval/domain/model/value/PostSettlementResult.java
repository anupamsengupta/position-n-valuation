package com.power.posval.domain.model.value;

import java.util.Objects;

/**
 * Result of a post-settlement run (S9b). Carries an operation type tag and
 * an optional message for non-trivial outcomes.
 * Use {@link #noOp()} when the post-settlement check determined that no
 * further action was required (e.g. delivery day not yet settled).
 * Pattern #3, S4.1.
 */
public record PostSettlementResult(
    String operationType,
    boolean actionRequired,
    String message
) {
    public PostSettlementResult {
        Objects.requireNonNull(operationType, "operationType");
        // message is nullable
    }

    /**
     * Factory for a no-operation result: settlement checked, no action required.
     */
    public static PostSettlementResult noOp() {
        return new PostSettlementResult("NO_OP", false, null);
    }

    /**
     * Factory for a result that signals a follow-up action is needed.
     *
     * @param operationType short identifier of the action type
     * @param message       human-readable description of the required action
     */
    public static PostSettlementResult actionRequired(String operationType, String message) {
        Objects.requireNonNull(operationType, "operationType");
        return new PostSettlementResult(operationType, true, message);
    }
}
