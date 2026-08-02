package com.power.posval.redis;

import com.power.posval.domain.model.QualityState;
import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.marketdata.MarketDataType;
import com.power.posval.domain.port.marketdata.VolSurfaceLookup;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RedisMarketDataCacheTest {

    private Map<String, String> store;
    private RedisMarketDataCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        store = new LinkedHashMap<>();
        // Dynamic proxy avoids implementing all 200+ RedisCommands methods
        RedisCommands<String, String> commands = (RedisCommands<String, String>)
            Proxy.newProxyInstance(
                RedisCommands.class.getClassLoader(),
                new Class[]{RedisCommands.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> store.get((String) args[0]);
                    case "setex" -> {
                        store.put((String) args[0], (String) args[2]);
                        yield "OK";
                    }
                    case "del" -> {
                        long count = 0;
                        for (Object k : args) {
                            if (k instanceof String[] keys) {
                                for (String key : keys) {
                                    if (store.remove(key) != null) count++;
                                }
                            } else if (k instanceof String key) {
                                if (store.remove(key) != null) count++;
                            }
                        }
                        yield count;
                    }
                    case "scan" -> {
                        var result = new KeyScanCursor<String>();
                        result.getKeys().addAll(new ArrayList<>(store.keySet()));
                        result.setCursor("0");
                        result.setFinished(true);
                        yield result;
                    }
                    case "isOpen" -> true;
                    case "ping" -> "PONG";
                    default -> null;
                });
        cache = new RedisMarketDataCache(commands);
    }

    @Test
    void putAndGetRoundTrip() {
        var lookup = new MarketDataLookup(
            new BigDecimal("42.50"), 5L, "EPEX_DA15",
            Instant.parse("2025-03-01T00:00:00Z"), QualityState.VALIDATED);

        cache.put("t1", MarketDataType.FIXING, "EPEX_DA15", "2025-03-01T00:00:00Z", lookup);
        var result = cache.get("t1", MarketDataType.FIXING, "EPEX_DA15", "2025-03-01T00:00:00Z");

        assertTrue(result.isPresent());
        assertEquals(new BigDecimal("42.50"), result.get().value());
        assertEquals(5L, result.get().versionId());
        assertEquals("EPEX_DA15", result.get().series());
        assertEquals(QualityState.VALIDATED, result.get().qualityState());
    }

    @Test
    void getMissReturnsEmpty() {
        var result = cache.get("t1", MarketDataType.FIXING, "MISSING", "key");
        assertTrue(result.isEmpty());
    }

    @Test
    void volSurfacePutAndGetRoundTrip() {
        var lookup = new VolSurfaceLookup(
            new BigDecimal("0.25"), 7L, "DE_POWER", 0.5, "3M",
            Instant.parse("2025-03-01T00:00:00Z"), QualityState.VALIDATED);

        cache.putVolSurface("t1", "DE_POWER", "0.5:3M:2025-03-01T00:00:00Z", lookup);
        var result = cache.getVolSurface("t1", "DE_POWER", "0.5:3M:2025-03-01T00:00:00Z");

        assertTrue(result.isPresent());
        assertEquals(new BigDecimal("0.25"), result.get().impliedVolatility());
        assertEquals("DE_POWER", result.get().surfaceId());
        assertEquals(0.5, result.get().strikeDelta());
        assertEquals("3M", result.get().expiryTenor());
    }

    @Test
    void invalidateDeletesMatchingKeys() {
        cache.put("t1", MarketDataType.FIXING, "EPEX", "k1",
            new MarketDataLookup(BigDecimal.ONE, 1L, "EPEX",
                Instant.now(), QualityState.VALIDATED));
        cache.put("t1", MarketDataType.FIXING, "EPEX", "k2",
            new MarketDataLookup(BigDecimal.TEN, 2L, "EPEX",
                Instant.now(), QualityState.VALIDATED));

        assertEquals(2, store.size());
        cache.invalidate("t1", MarketDataType.FIXING, "EPEX");
        assertEquals(0, store.size());
    }

    @Test
    void tenantIsolation() {
        var lookup = new MarketDataLookup(
            BigDecimal.ONE, 1L, "S", Instant.now(), QualityState.VALIDATED);

        cache.put("tenant-A", MarketDataType.FIXING, "S", "k", lookup);
        var resultA = cache.get("tenant-A", MarketDataType.FIXING, "S", "k");
        var resultB = cache.get("tenant-B", MarketDataType.FIXING, "S", "k");

        assertTrue(resultA.isPresent());
        assertTrue(resultB.isEmpty());
    }
}
