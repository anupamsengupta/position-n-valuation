package com.power.posval.app.cache;

import com.power.posval.domain.port.cache.MarketDataCache;
import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.marketdata.MarketDataType;
import com.power.posval.domain.port.marketdata.VolSurfaceLookup;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class InMemoryMarketDataCache implements MarketDataCache {

    private final ConcurrentHashMap<String, MarketDataLookup> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, VolSurfaceLookup> volStore = new ConcurrentHashMap<>();
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicInteger misses = new AtomicInteger();

    @Override
    public Optional<MarketDataLookup> get(String tenantId, MarketDataType type, String series, String lookupKey) {
        String key = tenantId + "|" + type + "|" + series + "|" + lookupKey;
        MarketDataLookup value = store.get(key);
        if (value != null) {
            hits.incrementAndGet();
            return Optional.of(value);
        }
        misses.incrementAndGet();
        return Optional.empty();
    }

    @Override
    public void put(String tenantId, MarketDataType type, String series, String lookupKey, MarketDataLookup value) {
        String key = tenantId + "|" + type + "|" + series + "|" + lookupKey;
        store.put(key, value);
    }

    @Override
    public Optional<VolSurfaceLookup> getVolSurface(String tenantId, String surfaceId, String lookupKey) {
        String key = tenantId + "|VOL|" + surfaceId + "|" + lookupKey;
        VolSurfaceLookup value = volStore.get(key);
        if (value != null) {
            hits.incrementAndGet();
            return Optional.of(value);
        }
        misses.incrementAndGet();
        return Optional.empty();
    }

    @Override
    public void putVolSurface(String tenantId, String surfaceId, String lookupKey, VolSurfaceLookup value) {
        String key = tenantId + "|VOL|" + surfaceId + "|" + lookupKey;
        volStore.put(key, value);
    }

    @Override
    public void invalidate(String tenantId, MarketDataType type, String series) {
        String prefix = tenantId + "|" + type + "|" + series + "|";
        store.keySet().removeIf(k -> k.startsWith(prefix));
    }

    @Override
    public void invalidate(String tenantId, MarketDataType type, String series,
                            Instant rangeStart, Instant rangeEnd) {
        invalidate(tenantId, type, series);
    }

    public int size() { return store.size() + volStore.size(); }
    public int getHits() { return hits.get(); }
    public int getMisses() { return misses.get(); }
}
