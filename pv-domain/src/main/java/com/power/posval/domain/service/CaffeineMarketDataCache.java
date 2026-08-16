package com.power.posval.domain.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.power.posval.domain.port.cache.MarketDataCache;
import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.marketdata.MarketDataType;
import com.power.posval.domain.port.marketdata.VolSurfaceLookup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Process-scoped Caffeine L1 cache decorator for {@link MarketDataCache}.
 * Sits in front of the L2 cache (Redis/InMemory) and provides sub-microsecond
 * lookups for hot market data entries.
 *
 * <p>Key format mirrors {@code InMemoryMarketDataCache}:
 * {@code "{tenantId}|{type}|{series}|{lookupKey}"}.
 *
 * <p>Invalidation: Caffeine doesn't support prefix scans, so series-level
 * invalidation iterates all L1 keys matching the prefix. This is acceptable
 * because invalidation is rare (market data corrections) and Caffeine's
 * ConcurrentHashMap iteration is lock-free.
 *
 * <p>Thread-safe: Caffeine caches are fully concurrent.
 */
public class CaffeineMarketDataCache implements MarketDataCache {

    private static final Logger log = LoggerFactory.getLogger(CaffeineMarketDataCache.class);

    private final MarketDataCache delegate;
    private final Cache<String, MarketDataLookup> mdCache;
    private final Cache<String, VolSurfaceLookup> volCache;

    public CaffeineMarketDataCache(MarketDataCache delegate, long maxSize, long ttlHours) {
        this.delegate = delegate;
        this.mdCache = Caffeine.newBuilder()
            .maximumSize(maxSize)
            .expireAfterWrite(ttlHours, TimeUnit.HOURS)
            .recordStats()
            .build();
        this.volCache = Caffeine.newBuilder()
            .maximumSize(maxSize / 10) // vol surfaces are much fewer
            .expireAfterWrite(ttlHours, TimeUnit.HOURS)
            .recordStats()
            .build();
        log.info("CaffeineMarketDataCache initialized: maxSize={}, ttlHours={}", maxSize, ttlHours);
    }

    private static String key(String tenantId, MarketDataType type, String series, String lookupKey) {
        return tenantId + "|" + type + "|" + series + "|" + lookupKey;
    }

    private static String volKey(String tenantId, String surfaceId, String lookupKey) {
        return tenantId + "|VOL|" + surfaceId + "|" + lookupKey;
    }

    @Override
    public Optional<MarketDataLookup> get(String tenantId, MarketDataType type,
                                           String series, String lookupKey) {
        String k = key(tenantId, type, series, lookupKey);
        MarketDataLookup l1 = mdCache.getIfPresent(k);
        if (l1 != null) {
            return Optional.of(l1);
        }
        // L1 miss → delegate to L2
        Optional<MarketDataLookup> l2 = delegate.get(tenantId, type, series, lookupKey);
        l2.ifPresent(value -> mdCache.put(k, value));
        return l2;
    }

    @Override
    public void put(String tenantId, MarketDataType type,
                    String series, String lookupKey, MarketDataLookup value) {
        String k = key(tenantId, type, series, lookupKey);
        mdCache.put(k, value);
        delegate.put(tenantId, type, series, lookupKey, value);
    }

    @Override
    public Optional<VolSurfaceLookup> getVolSurface(String tenantId, String surfaceId,
                                                     String lookupKey) {
        String k = volKey(tenantId, surfaceId, lookupKey);
        VolSurfaceLookup l1 = volCache.getIfPresent(k);
        if (l1 != null) {
            return Optional.of(l1);
        }
        Optional<VolSurfaceLookup> l2 = delegate.getVolSurface(tenantId, surfaceId, lookupKey);
        l2.ifPresent(value -> volCache.put(k, value));
        return l2;
    }

    @Override
    public void putVolSurface(String tenantId, String surfaceId,
                              String lookupKey, VolSurfaceLookup value) {
        String k = volKey(tenantId, surfaceId, lookupKey);
        volCache.put(k, value);
        delegate.putVolSurface(tenantId, surfaceId, lookupKey, value);
    }

    @Override
    public void invalidate(String tenantId, MarketDataType type, String series) {
        String prefix = tenantId + "|" + type + "|" + series + "|";
        long evicted = evictByPrefix(mdCache, prefix);
        if (evicted > 0) {
            log.debug("L1 invalidated {} entries for {}", evicted, prefix);
        }
        delegate.invalidate(tenantId, type, series);
    }

    @Override
    public void invalidate(String tenantId, MarketDataType type, String series,
                           Instant rangeStart, Instant rangeEnd) {
        // Caffeine has no range scan; evict entire series from L1, L2 handles range precision
        String prefix = tenantId + "|" + type + "|" + series + "|";
        long evicted = evictByPrefix(mdCache, prefix);
        if (evicted > 0) {
            log.debug("L1 range-invalidated {} entries for {} [{}, {})",
                evicted, prefix, rangeStart, rangeEnd);
        }
        delegate.invalidate(tenantId, type, series, rangeStart, rangeEnd);
    }

    private static <V> long evictByPrefix(Cache<String, V> cache, String prefix) {
        long count = 0;
        for (String k : cache.asMap().keySet()) {
            if (k.startsWith(prefix)) {
                cache.invalidate(k);
                count++;
            }
        }
        return count;
    }
}
