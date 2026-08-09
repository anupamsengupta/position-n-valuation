package com.power.posval.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Domain model for a settlement cell (S5a).
 * Append-only; versioning is derived from the parent position entry's
 * bitemporal state — settlement cells do not carry their own bitemporal axes.
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
    BigDecimal marketPrice,      // nullable — mark-to-market price
    BigDecimal marketAmount,     // nullable — marketPrice × energy
    BigDecimal pnl,              // nullable — marketAmount - amount
    String currency,
    Set<String> activeLeaves,
    Map<String, Long> inputVersionSet,
    Instant computedAt
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
        java.util.Objects.requireNonNull(computedAt, "computedAt");
    }
}
