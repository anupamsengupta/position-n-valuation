package com.power.posval.app.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * DTO for a single settlement interval row in the DA settlement grid.
 *
 * <p>Flattens {@code SettlementCell} + its parent position's trade metadata
 * into a single flat record for the UI table. Nullable fields are marked.
 *
 * <p>Simulator-scope (pv-app). S16.2.2, DA-UI-02.
 */
public record DaSettlementRowDto(
    UUID cellId,
    UUID positionId,
    String tradeId,
    String tradeLegId,
    String direction,        // "BUY" or "SELL"
    Instant intervalStart,
    Instant intervalEnd,
    BigDecimal price,
    BigDecimal volumeMw,
    BigDecimal volumeMwh,
    BigDecimal amount,
    BigDecimal marketPrice,  // nullable
    BigDecimal marketAmount, // nullable
    BigDecimal pnl,          // nullable
    String currency,
    String cellStatus,
    String valuationType
) {}
