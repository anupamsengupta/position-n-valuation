package com.power.posval.domain.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Port interface for forward mark storage. Ephemeral — no history, no bitemporality.
 * FR-075: current state only, overwritten on revaluation.
 * S5b.
 */
public interface ForwardMarkStore {

    /** Write or overwrite a forward mark for a position × interval. */
    void put(String tenantId, UUID positionId,
             Instant intervalStart, Instant intervalEnd,
             BigDecimal markValue, String currency,
             Map<String, Long> inputVersionSet);

    /** Read current forward mark. Optional.empty() if none exists. */
    Optional<ForwardMark> get(String tenantId, UUID positionId,
                               Instant intervalStart);

    /** Bulk read for a position across an interval range. */
    List<ForwardMark> getRange(String tenantId, UUID positionId,
                                Instant rangeStart, Instant rangeEnd);

    /** Remove all marks for a position (on cancellation). */
    void removeAll(String tenantId, UUID positionId);
}
