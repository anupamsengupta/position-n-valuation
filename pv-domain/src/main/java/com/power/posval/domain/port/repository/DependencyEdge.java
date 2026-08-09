package com.power.posval.domain.port.repository;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * A reverse-dependency edge: cell X depends on input series Y.
 * Carries active_leaves for blast-radius optimization (FR-103).
 * affectedRangeStart/End are the cell's exact interval boundaries (15-min precision).
 * FR-102, FR-103, FR-104, S8.
 */
public record DependencyEdge(
    String tenantId,
    UUID cellId,
    String cellType,
    String inputSeriesKey,
    String inputType,
    Instant affectedRangeStart,
    Instant affectedRangeEnd,
    Set<String> activeLeaves,
    Instant createdAt,
    Instant prunedAt
) {}
