package com.power.posval.domain.port.cache;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Cached netted interval in S6 slot cache. Dual-unit (MW + MWh).
 * FR-080: grain = (delivery_point, portfolio, position_type) × atomic interval.
 * Pattern #29, FR-080–FR-085.
 */
public record CachedInterval(
    Instant intervalStart,
    Instant intervalEnd,
    BigDecimal netMw,
    BigDecimal netMwh,
    boolean isPeak,
    String calendarVersion,
    String versionHash
) {}
