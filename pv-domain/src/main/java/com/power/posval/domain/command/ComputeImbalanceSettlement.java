package com.power.posval.domain.command;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Command to compute per-interval imbalance settlement records for a balancing group
 * on a single delivery day. The service will load nominated volumes from
 * NominationRepository and actual metered volumes from MarketDataPort (reBAP prices
 * and TSO metered data), then persist ImbalanceRecord rows.
 * Pattern #17, S4.6, DA-SET-04.
 */
public record ComputeImbalanceSettlement(
    String tenantId,
    String balancingGroupId,
    LocalDate deliveryDay
) {
    public ComputeImbalanceSettlement {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(balancingGroupId, "balancingGroupId");
        Objects.requireNonNull(deliveryDay, "deliveryDay");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (balancingGroupId.isBlank()) {
            throw new IllegalArgumentException("balancingGroupId must not be blank");
        }
    }
}
