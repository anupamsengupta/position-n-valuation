package com.power.posval.domain.port.repository;

import com.power.posval.domain.model.TimeGranularity;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Materialized rollup cell.
 * FR-090: per (delivery_point, portfolio, position_type) × period × peak/off-peak.
 * FR-035: netMw = time-weighted average; netMwh = sum; amounts = sum.
 */
public record RollupCell(
    Instant periodStart,
    Instant periodEnd,
    TimeGranularity granularity,
    String deliveryPointId,
    String portfolioId,
    boolean isPeak,
    BigDecimal netMw,
    BigDecimal netMwh,
    BigDecimal price,           // volume-weighted average trade price (settledValue / netMwh)
    BigDecimal marketPrice,     // volume-weighted average market price (marketValue / netMwh)
    BigDecimal settledValue,
    BigDecimal marketValue,
    BigDecimal pnl,
    BigDecimal forwardMarkValue,
    String currency,
    String calendarVersion,
    String versionHash
) {}
