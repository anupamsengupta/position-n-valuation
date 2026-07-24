package com.power.posval.domain.port.cache;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Pre-multiplied trade interval record.
 * FR-086a: content per cell. D-12: commodity-neutral column names.
 */
public record TradeIntervalRecord(
    String tradeLegId,
    Instant intervalStart,
    Instant intervalEnd,
    BigDecimal resolvedQty,
    BigDecimal resolvedEnergy,
    BigDecimal multiplier,
    String seriesKey,
    String versionHash
) {}
