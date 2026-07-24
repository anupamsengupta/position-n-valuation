package com.power.posval.redis;

import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.port.cache.CachedInterval;
import com.power.posval.domain.port.cache.VolumeCache;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Redis adapter for VolumeCache. §S6, Pattern #29/#30/#31.
 * Key: vol:{tenant}:{series}:{start_iso}
 * TTL: 24h. MGET for bulk reads. SCAN+DEL for invalidation.
 */
public class RedisVolumeCache implements VolumeCache {

    private static final long TTL_SECONDS = Duration.ofHours(24).toSeconds();
    private static final String KEY_PREFIX = "vol:";

    private final RedisCommands<String, String> commands;

    @Inject
    public RedisVolumeCache(RedisCommands<String, String> commands) {
        this.commands = commands;
    }

    @Override
    public Optional<CachedInterval> get(String tenantId, String seriesKey,
                                          Instant intervalStart, boolean readConsistent) {
        if (readConsistent) {
            return Optional.empty(); // bypass cache for consistent reads
        }
        String key = key(tenantId, seriesKey, intervalStart);
        String value = commands.get(key);
        return Optional.ofNullable(value).map(this::deserialize);
    }

    @Override
    public List<CachedInterval> getAll(String tenantId, String seriesKey,
                                         List<Instant> intervalStarts) {
        if (intervalStarts.isEmpty()) return List.of();

        String[] keys = intervalStarts.stream()
            .map(s -> key(tenantId, seriesKey, s))
            .toArray(String[]::new);

        List<io.lettuce.core.KeyValue<String, String>> results = commands.mget(keys);
        List<CachedInterval> intervals = new ArrayList<>();
        for (var kv : results) {
            if (kv.hasValue()) {
                intervals.add(deserialize(kv.getValue()));
            }
        }
        return intervals;
    }

    @Override
    public void put(String tenantId, String seriesKey,
                     Instant intervalStart, CachedInterval value) {
        String key = key(tenantId, seriesKey, intervalStart);
        commands.setex(key, TTL_SECONDS, serialize(value));
    }

    @Override
    public void putAll(String tenantId, String seriesKey,
                        Map<Instant, CachedInterval> values) {
        // Pipeline batch writes
        values.forEach((start, interval) -> {
            String key = key(tenantId, seriesKey, start);
            commands.setex(key, TTL_SECONDS, serialize(interval));
        });
    }

    @Override
    public void invalidate(String tenantId, String seriesKey,
                            DeliveryRange affectedRange) {
        // SCAN + DEL for keys matching pattern
        String pattern = KEY_PREFIX + tenantId + ":" + seriesKey + ":*";
        io.lettuce.core.ScanArgs scanArgs = io.lettuce.core.ScanArgs.Builder.matches(pattern).limit(1000);
        var cursor = io.lettuce.core.ScanCursor.INITIAL;

        Instant rangeStart = affectedRange.startInstant().toInstant();
        Instant rangeEnd = affectedRange.endInstant().toInstant();

        do {
            var result = commands.scan(cursor, scanArgs);
            cursor = result;
            for (String key : result.getKeys()) {
                Instant keyStart = parseInstantFromKey(key);
                if (keyStart != null && !keyStart.isBefore(rangeStart) && keyStart.isBefore(rangeEnd)) {
                    commands.del(key);
                }
            }
        } while (!cursor.isFinished());
    }

    @Override
    public void invalidateAll(String tenantId, String seriesKey) {
        String pattern = KEY_PREFIX + tenantId + ":" + seriesKey + ":*";
        io.lettuce.core.ScanArgs scanArgs = io.lettuce.core.ScanArgs.Builder.matches(pattern).limit(1000);
        var cursor = io.lettuce.core.ScanCursor.INITIAL;

        do {
            var result = commands.scan(cursor, scanArgs);
            cursor = result;
            for (String key : result.getKeys()) {
                commands.del(key);
            }
        } while (!cursor.isFinished());
    }

    private String key(String tenantId, String seriesKey, Instant start) {
        return KEY_PREFIX + tenantId + ":" + seriesKey + ":" + start;
    }

    private Instant parseInstantFromKey(String key) {
        try {
            int lastColon = key.lastIndexOf(':');
            return Instant.parse(key.substring(lastColon + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private String serialize(CachedInterval ci) {
        // Simple delimited format: netMw|netMwh|isPeak|calendarVersion|versionHash|start|end
        return ci.netMw() + "|" + ci.netMwh() + "|" + ci.isPeak()
            + "|" + ci.calendarVersion() + "|" + ci.versionHash()
            + "|" + ci.intervalStart() + "|" + ci.intervalEnd();
    }

    private CachedInterval deserialize(String s) {
        String[] parts = s.split("\\|", 7);
        return new CachedInterval(
            Instant.parse(parts[5]),
            Instant.parse(parts[6]),
            new BigDecimal(parts[0]),
            new BigDecimal(parts[1]),
            Boolean.parseBoolean(parts[2]),
            parts[3],
            parts[4]);
    }
}
