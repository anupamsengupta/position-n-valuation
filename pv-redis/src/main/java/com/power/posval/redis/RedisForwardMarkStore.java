package com.power.posval.redis;

import com.power.posval.domain.port.ForwardMark;
import com.power.posval.domain.port.ForwardMarkStore;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Redis adapter for ForwardMarkStore. §S5b.
 * Key: fwd:{tenant}:{position}:{start_iso}
 * TTL: 5 minutes (ephemeral forward marks).
 */
public class RedisForwardMarkStore implements ForwardMarkStore {

    private static final long TTL_SECONDS = Duration.ofMinutes(5).toSeconds();
    private static final String KEY_PREFIX = "fwd:";

    private final RedisCommands<String, String> commands;

    @Inject
    public RedisForwardMarkStore(RedisCommands<String, String> commands) {
        this.commands = commands;
    }

    @Override
    public void put(String tenantId, UUID positionId,
                     Instant intervalStart, Instant intervalEnd,
                     BigDecimal markValue, String currency,
                     Map<String, Long> inputVersionSet) {
        String key = key(tenantId, positionId, intervalStart);
        String value = markValue + "|" + currency + "|" + intervalEnd
            + "|" + inputVersionSet;
        commands.setex(key, TTL_SECONDS, value);
    }

    @Override
    public Optional<ForwardMark> get(String tenantId, UUID positionId,
                                       Instant intervalStart) {
        String key = key(tenantId, positionId, intervalStart);
        String value = commands.get(key);
        return Optional.ofNullable(value)
            .map(v -> deserialize(positionId, intervalStart, v));
    }

    @Override
    public List<ForwardMark> getRange(String tenantId, UUID positionId,
                                        Instant rangeStart, Instant rangeEnd) {
        // SCAN for matching keys in range
        String pattern = KEY_PREFIX + tenantId + ":" + positionId + ":*";
        io.lettuce.core.ScanArgs scanArgs = io.lettuce.core.ScanArgs.Builder
            .matches(pattern).limit(1000);
        var cursor = io.lettuce.core.ScanCursor.INITIAL;
        List<ForwardMark> results = new ArrayList<>();

        do {
            var result = commands.scan(cursor, scanArgs);
            cursor = result;
            for (String key : result.getKeys()) {
                Instant start = parseInstantFromKey(key);
                if (start != null && !start.isBefore(rangeStart) && start.isBefore(rangeEnd)) {
                    String val = commands.get(key);
                    if (val != null) {
                        results.add(deserialize(positionId, start, val));
                    }
                }
            }
        } while (!cursor.isFinished());

        results.sort(Comparator.comparing(ForwardMark::intervalStart));
        return results;
    }

    @Override
    public void removeAll(String tenantId, UUID positionId) {
        String pattern = KEY_PREFIX + tenantId + ":" + positionId + ":*";
        io.lettuce.core.ScanArgs scanArgs = io.lettuce.core.ScanArgs.Builder
            .matches(pattern).limit(1000);
        var cursor = io.lettuce.core.ScanCursor.INITIAL;

        do {
            var result = commands.scan(cursor, scanArgs);
            cursor = result;
            for (String key : result.getKeys()) {
                commands.del(key);
            }
        } while (!cursor.isFinished());
    }

    private String key(String tenantId, UUID positionId, Instant start) {
        return KEY_PREFIX + tenantId + ":" + positionId + ":" + start;
    }

    private Instant parseInstantFromKey(String key) {
        try {
            int lastColon = key.lastIndexOf(':');
            return Instant.parse(key.substring(lastColon + 1));
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private ForwardMark deserialize(UUID positionId, Instant start, String value) {
        String[] parts = value.split("\\|", 4);
        BigDecimal markValue = new BigDecimal(parts[0]);
        String currency = parts[1];
        Instant end = Instant.parse(parts[2]);
        // inputVersionSet serialized as map toString — simplified
        return new ForwardMark(positionId, start, end, markValue, currency, Map.of());
    }
}
