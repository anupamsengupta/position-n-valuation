package com.power.posval.guice;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import com.power.posval.domain.port.cache.VolumeCache;
import com.power.posval.redis.RedisVolumeCache;

/**
 * Guice module for cache bindings. §16.5.
 * VolumeCache → RedisVolumeCache.
 */
public class CacheModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(VolumeCache.class)
            .to(RedisVolumeCache.class)
            .in(Singleton.class);
    }
}
