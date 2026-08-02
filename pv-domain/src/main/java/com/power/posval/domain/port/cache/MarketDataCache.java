package com.power.posval.domain.port.cache;

import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.marketdata.MarketDataType;
import com.power.posval.domain.port.marketdata.VolSurfaceLookup;

import java.time.Instant;
import java.util.Optional;

/**
 * Port interface for the market data cache layer.
 * Pattern #29 (read-through), #30 (event invalidation).
 * Mirrors {@link VolumeCache} pattern with type-discriminated keys.
 *
 * <p>{@code lookupKey} is a pre-formatted string encoding type-specific dimensions:
 * <ul>
 *   <li>Fixings/Spreads: ISO instant string</li>
 *   <li>Forward curves: {@code {pillar}:{asOfDate}}</li>
 *   <li>FX: ISO instant string</li>
 *   <li>Indices: refMonthExpression</li>
 *   <li>Vol surfaces: {@code {strikeDelta}:{expiryTenor}:{asOfDate}}</li>
 * </ul>
 */
public interface MarketDataCache {

    Optional<MarketDataLookup> get(String tenantId, MarketDataType type,
                                    String series, String lookupKey);

    void put(String tenantId, MarketDataType type,
             String series, String lookupKey, MarketDataLookup value);

    Optional<VolSurfaceLookup> getVolSurface(String tenantId, String surfaceId,
                                              String lookupKey);

    void putVolSurface(String tenantId, String surfaceId,
                       String lookupKey, VolSurfaceLookup value);

    void invalidate(String tenantId, MarketDataType type, String series);

    void invalidate(String tenantId, MarketDataType type, String series,
                    Instant rangeStart, Instant rangeEnd);
}
