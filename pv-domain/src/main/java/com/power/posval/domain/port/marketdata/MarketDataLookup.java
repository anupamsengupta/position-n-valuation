package com.power.posval.domain.port.marketdata;

import com.power.posval.domain.model.QualityState;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Single market data observation returned by {@link MarketDataPort}.
 * FR-056: every valuation cell records the version_id of each input.
 */
public record MarketDataLookup(
    BigDecimal value,
    long versionId,
    String series,
    Instant referenceTime,
    QualityState qualityState
) {}
