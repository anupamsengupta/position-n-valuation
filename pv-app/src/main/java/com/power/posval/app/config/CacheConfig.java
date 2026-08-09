package com.power.posval.app.config;

import com.power.posval.domain.port.cache.MarketDataCache;
import com.power.posval.domain.port.cache.VolumeCache;
import com.power.posval.redis.RedisCacheTtl;
import com.power.posval.redis.RedisMarketDataCache;
import com.power.posval.redis.RedisVolumeCache;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class CacheConfig {

    @Value("${pv.redis.host:localhost}")
    private String redisHost;

    @Value("${pv.redis.port:6379}")
    private int redisPort;

    @Value("${pv.redis.password:}")
    private String redisPassword;

    @Value("${pv.redis.timeout-seconds:5}")
    private int redisTimeoutSeconds;

    // TTL settings (externalized, with production defaults)
    @Value("${pv.cache.ttl.market-data-stable-hours:24}")
    private int marketDataStableHours;

    @Value("${pv.cache.ttl.market-data-volatile-hours:1}")
    private int marketDataVolatileHours;

    @Value("${pv.cache.ttl.volume-hours:24}")
    private int volumeHours;

    @Value("${pv.cache.ttl.forward-mark-minutes:5}")
    private int forwardMarkMinutes;

    @Value("${pv.cache.scan-batch-size:1000}")
    private int scanBatchSize;

    @Bean(destroyMethod = "shutdown")
    public RedisClient redisClient() {
        var builder = RedisURI.builder()
                .withHost(redisHost)
                .withPort(redisPort)
                .withTimeout(Duration.ofSeconds(redisTimeoutSeconds));
        if (redisPassword != null && !redisPassword.isBlank()) {
            builder.withPassword(redisPassword.toCharArray());
        }
        return RedisClient.create(builder.build());
    }

    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, String> redisConnection(RedisClient redisClient) {
        return redisClient.connect();
    }

    @Bean
    public RedisCommands<String, String> redisCommands(StatefulRedisConnection<String, String> connection) {
        return connection.sync();
    }

    @Bean
    public RedisCacheTtl redisCacheTtl() {
        return new RedisCacheTtl(
            Duration.ofHours(marketDataStableHours),
            Duration.ofHours(marketDataVolatileHours),
            Duration.ofHours(volumeHours),
            Duration.ofMinutes(forwardMarkMinutes),
            scanBatchSize);
    }

    @Bean
    public MarketDataCache marketDataCache(RedisCommands<String, String> redisCommands,
                                            RedisCacheTtl ttl) {
        return new RedisMarketDataCache(redisCommands, ttl);
    }

    @Bean
    public VolumeCache volumeCache(RedisCommands<String, String> redisCommands,
                                    RedisCacheTtl ttl) {
        return new RedisVolumeCache(redisCommands, ttl);
    }
}
