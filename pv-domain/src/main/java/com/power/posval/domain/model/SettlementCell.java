package com.power.posval.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Domain model for a settlement cell (S5a).
 * Bitemporal, append-only.
 * FR-070, FR-071, Pattern #1.
 */
public record SettlementCell(
    UUID cellId,
    String tenantId,
    UUID positionId,
    Instant intervalStart,
    Instant intervalEnd,
    String valuationType,
    String cellStatus,
    BigDecimal price,
    BigDecimal volumeMw,
    BigDecimal volumeMwh,
    BigDecimal amount,
    String currency,
    Set<String> activeLeaves,
    Map<String, Long> inputVersionSet,
    Instant validFrom,
    Instant validTo,
    Instant knownFrom,
    Instant knownTo
) {
    public SettlementCell {
        java.util.Objects.requireNonNull(cellId, "cellId");
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(positionId, "positionId");
        java.util.Objects.requireNonNull(intervalStart, "intervalStart");
        java.util.Objects.requireNonNull(intervalEnd, "intervalEnd");
        java.util.Objects.requireNonNull(price, "price");
        java.util.Objects.requireNonNull(amount, "amount");
        java.util.Objects.requireNonNull(currency, "currency");
    }

    public boolean isCurrentKnowledge() { return knownTo == null; }
}
