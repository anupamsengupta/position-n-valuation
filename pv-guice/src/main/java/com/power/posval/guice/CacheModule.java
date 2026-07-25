package com.power.posval.guice;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.power.posval.domain.port.cache.VolumeCache;
import com.power.posval.redis.RedisVolumeCache;
import com.power.posval.redis.provider.RedisConnectionProvider;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Guice module for cache bindings. §16.5.
 * VolumeCache → RedisVolumeCache, RedisCommands provider.
 */
public class CacheModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(new TypeLiteral<RedisCommands<String, String>>() {})
            .toProvider(RedisConnectionProvider.class)
            .in(Singleton.class);

        bind(VolumeCache.class)
            .to(RedisVolumeCache.class)
            .in(Singleton.class);
    }
}
