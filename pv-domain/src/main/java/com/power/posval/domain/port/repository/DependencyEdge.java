package com.power.posval.domain.port.repository;

import com.power.posval.domain.model.value.DeliveryRange;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * A reverse-dependency edge: cell X depends on input series Y.
 * Carries active_leaves for blast-radius optimization (FR-103).
 * FR-102, FR-103, FR-104, S8.
 */
public record DependencyEdge(
    String tenantId,
    UUID cellId,
    String cellType,
    String inputSeriesKey,
    String inputType,
    DeliveryRange affectedRange,
    Set<String> activeLeaves,
    Instant createdAt,
    Instant prunedAt
) {}
