package com.power.posval.app.cache;

import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.port.cache.CachedInterval;
import com.power.posval.domain.port.cache.VolumeCache;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class InMemoryVolumeCache implements VolumeCache {

    private final ConcurrentHashMap<String, CachedInterval> store = new ConcurrentHashMap<>();

    @Override
    public Optional<CachedInterval> get(String tenantId, String seriesKey, Instant intervalStart, boolean readConsistent) {
        return Optional.ofNullable(store.get(key(tenantId, seriesKey, intervalStart)));
    }

    @Override
    public List<CachedInterval> getAll(String tenantId, String seriesKey, List<Instant> intervalStarts) {
        return intervalStarts.stream()
                .map(start -> store.get(key(tenantId, seriesKey, start)))
                .filter(v -> v != null)
                .collect(Collectors.toList());
    }

    @Override
    public void put(String tenantId, String seriesKey, Instant intervalStart, CachedInterval value) {
        store.put(key(tenantId, seriesKey, intervalStart), value);
    }

    @Override
    public void putAll(String tenantId, String seriesKey, Map<Instant, CachedInterval> values) {
        values.forEach((start, val) -> store.put(key(tenantId, seriesKey, start), val));
    }

    @Override
    public void invalidate(String tenantId, String seriesKey, DeliveryRange affectedRange) {
        String prefix = tenantId + "|" + seriesKey + "|";
        store.keySet().removeIf(k -> k.startsWith(prefix));
    }

    @Override
    public void invalidateAll(String tenantId, String seriesKey) {
        String prefix = tenantId + "|" + seriesKey + "|";
        store.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public int size() { return store.size(); }

    private String key(String tenantId, String seriesKey, Instant intervalStart) {
        return tenantId + "|" + seriesKey + "|" + intervalStart;
    }
}
