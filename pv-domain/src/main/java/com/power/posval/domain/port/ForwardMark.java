package com.power.posval.domain.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Forward mark value record. Ephemeral, no version tracking.
 * FR-075, D-3, S5b.
 */
public record ForwardMark(
    UUID positionId,
    Instant intervalStart,
    Instant intervalEnd,
    BigDecimal markValue,
    String currency,
    Map<String, Long> inputVersionSet
) {}
