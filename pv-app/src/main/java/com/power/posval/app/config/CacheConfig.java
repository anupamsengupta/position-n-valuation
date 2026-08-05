package com.power.posval.app.config;

import com.power.posval.domain.port.cache.MarketDataCache;
import com.power.posval.domain.port.cache.VolumeCache;
import com.power.posval.redis.RedisMarketDataCache;
import com.power.posval.redis.RedisVolumeCache;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CacheConfig {

    @Value("${pv.redis.host:localhost}")
    private String redisHost;

    @Value("${pv.redis.port:6379}")
    private int redisPort;

    @Bean(destroyMethod = "shutdown")
    public RedisClient redisClient() {
        RedisURI uri = RedisURI.builder()
                .withHost(redisHost)
                .withPort(redisPort)
                .build();
        return RedisClient.create(uri);
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
    public MarketDataCache marketDataCache(RedisCommands<String, String> redisCommands) {
        return new RedisMarketDataCache(redisCommands);
    }

    @Bean
    public VolumeCache volumeCache(RedisCommands<String, String> redisCommands) {
        return new RedisVolumeCache(redisCommands);
    }
}
