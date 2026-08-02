package com.power.posval.domain.port.marketdata;

import com.power.posval.domain.model.QualityState;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Single volatility surface observation returned by {@link MarketDataPort#lookupVolSurface}.
 * Keyed by (surfaceId, strikeDelta, expiryTenor, asOfDate).
 */
public record VolSurfaceLookup(
    BigDecimal impliedVolatility,
    long versionId,
    String surfaceId,
    double strikeDelta,
    String expiryTenor,
    Instant observationDate,
    QualityState qualityState
) {}
