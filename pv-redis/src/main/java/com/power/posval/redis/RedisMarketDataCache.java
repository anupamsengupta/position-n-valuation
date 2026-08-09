package com.power.posval.redis;

import com.power.posval.domain.model.QualityState;
import com.power.posval.domain.port.cache.MarketDataCache;
import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.marketdata.MarketDataType;
import com.power.posval.domain.port.marketdata.VolSurfaceLookup;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * Redis adapter for {@link MarketDataCache}. Pattern #29/#30.
 * Key: md:{TYPE}:{tenant}:{series}:{lookupKey}
 * Stable data TTL: 24h. Volatile data TTL: 1h.
 * Serialization: pipe-delimited strings matching RedisVolumeCache pattern.
 */
public class RedisMarketDataCache implements MarketDataCache {

    private static final String KEY_PREFIX = "md:";

    private final RedisCommands<String, String> commands;
    private final RedisCacheTtl ttl;

    @Inject
    public RedisMarketDataCache(RedisCommands<String, String> commands) {
        this(commands, RedisCacheTtl.DEFAULTS);
    }

    public RedisMarketDataCache(RedisCommands<String, String> commands, RedisCacheTtl ttl) {
        this.commands = commands;
        this.ttl = ttl;
    }

    @Override
    public Optional<MarketDataLookup> get(String tenantId, MarketDataType type,
                                            String series, String lookupKey) {
        String key = key(type, tenantId, series, lookupKey);
        String value = commands.get(key);
        return Optional.ofNullable(value).map(this::deserializeLookup);
    }

    @Override
    public void put(String tenantId, MarketDataType type,
                     String series, String lookupKey, MarketDataLookup value) {
        String key = key(type, tenantId, series, lookupKey);
        commands.setex(key, ttlFor(type), serializeLookup(value));
    }

    @Override
    public Optional<VolSurfaceLookup> getVolSurface(String tenantId, String surfaceId,
                                                      String lookupKey) {
        String key = key(MarketDataType.VOL_SURFACE, tenantId, surfaceId, lookupKey);
        String value = commands.get(key);
        return Optional.ofNullable(value).map(this::deserializeVolSurface);
    }

    @Override
    public void putVolSurface(String tenantId, String surfaceId,
                               String lookupKey, VolSurfaceLookup value) {
        String key = key(MarketDataType.VOL_SURFACE, tenantId, surfaceId, lookupKey);
        commands.setex(key, ttl.marketDataVolatileSeconds(), serializeVolSurface(value));
    }

    @Override
    public void invalidate(String tenantId, MarketDataType type, String series) {
        String pattern = KEY_PREFIX + type.name() + ":" + tenantId + ":" + series + ":*";
        scanAndDelete(pattern);
    }

    @Override
    public void invalidate(String tenantId, MarketDataType type, String series,
                            Instant rangeStart, Instant rangeEnd) {
        // For simplicity and correctness, invalidate the full series.
        // Range-based key filtering is complex with pre-formatted lookupKeys.
        invalidate(tenantId, type, series);
    }

    // --- key construction ---

    private String key(MarketDataType type, String tenantId, String series, String lookupKey) {
        return KEY_PREFIX + type.name() + ":" + tenantId + ":" + series + ":" + lookupKey;
    }

    private long ttlFor(MarketDataType type) {
        return switch (type) {
            case FORWARD_CURVE, VOL_SURFACE -> ttl.marketDataVolatileSeconds();
            case FIXING, FX_RATE, INDEX, SPREAD -> ttl.marketDataStableSeconds();
        };
    }

    // --- SCAN+DEL invalidation ---

    private void scanAndDelete(String pattern) {
        var scanArgs = io.lettuce.core.ScanArgs.Builder.matches(pattern).limit(ttl.scanBatchSize());
        var cursor = io.lettuce.core.ScanCursor.INITIAL;
        do {
            var result = commands.scan(cursor, scanArgs);
            cursor = result;
            for (String key : result.getKeys()) {
                commands.del(key);
            }
        } while (!cursor.isFinished());
    }

    // --- serialization (pipe-delimited) ---

    private String serializeLookup(MarketDataLookup l) {
        return l.value() + "|" + l.versionId() + "|" + l.series()
            + "|" + l.referenceTime() + "|" + l.qualityState().name();
    }

    private MarketDataLookup deserializeLookup(String s) {
        String[] parts = s.split("\\|", 5);
        return new MarketDataLookup(
            new BigDecimal(parts[0]),
            Long.parseLong(parts[1]),
            parts[2],
            Instant.parse(parts[3]),
            QualityState.valueOf(parts[4]));
    }

    private String serializeVolSurface(VolSurfaceLookup v) {
        return v.impliedVolatility() + "|" + v.versionId() + "|" + v.surfaceId()
            + "|" + v.strikeDelta() + "|" + v.expiryTenor()
            + "|" + v.observationDate() + "|" + v.qualityState().name();
    }

    private VolSurfaceLookup deserializeVolSurface(String s) {
        String[] parts = s.split("\\|", 7);
        return new VolSurfaceLookup(
            new BigDecimal(parts[0]),
            Long.parseLong(parts[1]),
            parts[2],
            Double.parseDouble(parts[3]),
            parts[4],
            Instant.parse(parts[5]),
            QualityState.valueOf(parts[6]));
    }
}
