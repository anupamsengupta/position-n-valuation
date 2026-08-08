package com.power.posval.domain.model;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.UUID;

/**
 * Query-time aggregation of settlement cells per position × delivery-month.
 * Computed on read from S5a (settlement cells) — not persisted.
 * FR-035: MW = time-weighted average on roll-up; MWh = sum on roll-up.
 * FR-090: net amounts and volumes aggregated per position period.
 */
public record PositionMonthSummary(
    UUID positionId,
    String tenantId,
    String tradeId,
    String tradeLegId,
    YearMonth deliveryMonth,
    BigDecimal totalMwh,
    BigDecimal avgMw,
    BigDecimal totalAmount,
    BigDecimal totalMarketAmount,
    BigDecimal totalPnl,
    BigDecimal avgPrice,
    BigDecimal avgMarketPrice,
    String currency,
    int cellCount
) {}
