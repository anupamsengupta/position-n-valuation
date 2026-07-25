package com.power.posval.redis.provider;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

/**
 * Guice provider for Lettuce RedisCommands.
 * Creates a connection from system properties.
 */
@Singleton
public class RedisConnectionProvider implements Provider<RedisCommands<String, String>> {

    private volatile RedisCommands<String, String> commands;

    @Override
    public RedisCommands<String, String> get() {
        if (commands == null) {
            synchronized (this) {
                if (commands == null) {
                    String host = System.getProperty("pv.redis.host", "localhost");
                    int port = Integer.getInteger("pv.redis.port", 6379);
                    RedisURI uri = RedisURI.builder().withHost(host).withPort(port).build();
                    RedisClient client = RedisClient.create(uri);
                    StatefulRedisConnection<String, String> conn = client.connect();
                    commands = conn.sync();
                }
            }
        }
        return commands;
    }
}
