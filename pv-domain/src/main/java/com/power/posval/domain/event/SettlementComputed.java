package com.power.posval.domain.event;

import com.power.posval.domain.model.value.Money;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Emitted when a settlement cell is created or re-versioned.
 * Drives dependency-index edge updates (S8).
 * Pattern #14, FR-071, S5a/S8.
 */
public record SettlementComputed(
    UUID positionId,
    ZonedDateTime intervalStart,
    ZonedDateTime intervalEnd,
    Money value,
    String status,             // "PROVISIONAL" or "FINAL"
    Set<String> activeLeaves,
    Map<String, Long> inputVersionSet,
    Instant eventTime
) {}
