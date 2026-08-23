package com.power.posval.app.dto;

import java.util.UUID;

/**
 * DTO for a balancing group entry in the dropdown list.
 *
 * <p>Returned by {@code GET /api/da/nominations/balancing-groups}.
 * Matches {@code BalancingGroupItem} in the UI spec S7.3.
 *
 * <p>Simulator-scope (pv-app). S16.2.2, DA-UI-03.
 */
public record BalancingGroupDto(
    UUID bgId,
    String tsoArea,
    String bgCode
) {}
