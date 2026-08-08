package com.power.posval.app.dto;

import com.power.posval.domain.event.MarketDataUpdated;
import com.power.posval.domain.port.marketdata.MarketDataType;

import java.time.Instant;

/**
 * REST DTO for triggering a {@link MarketDataUpdated} event through the outbox → Kafka pipeline.
 * Used for testing the full production flow (cache invalidation → S8 dependency lookup → revaluation).
 */
public record MarketDataUpdatedRequest(
        String tenantId,
        String dataType,         // FIXING, FORWARD_CURVE, FX_RATE, INDEX, VOL_SURFACE, SPREAD
        String series,           // e.g., "EPEX_DA15", "EEX_BASE"
        String affectedRangeStart, // nullable ISO-8601 instant → null means full series
        String affectedRangeEnd,   // nullable ISO-8601 instant
        long newVersionId
) {
    public MarketDataUpdated toEvent() {
        return new MarketDataUpdated(
                tenantId,
                MarketDataType.valueOf(dataType),
                series,
                affectedRangeStart != null ? Instant.parse(affectedRangeStart) : null,
                affectedRangeEnd != null ? Instant.parse(affectedRangeEnd) : null,
                newVersionId,
                Instant.now());
    }
}
