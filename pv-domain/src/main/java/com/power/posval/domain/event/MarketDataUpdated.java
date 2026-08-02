package com.power.posval.domain.event;

import com.power.posval.domain.port.marketdata.MarketDataType;

import java.time.Instant;

/**
 * Cache invalidation trigger for market data changes.
 * Published when market data is ingested or corrected.
 * Consumed by {@code MarketDataUpdatedConsumer} to invalidate Redis cache.
 * S4, Pattern #30.
 */
public record MarketDataUpdated(
    String tenantId,
    MarketDataType dataType,
    String series,
    Instant affectedRangeStart,   // nullable → full series invalidation
    Instant affectedRangeEnd,     // nullable
    long newVersionId,
    Instant eventTime
) {}
