package com.power.posval.app.controller;

import com.power.posval.app.dto.ApiResponse;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final EntityManagerFactory emf;
    private final RedisCommands<String, String> redisCommands;

    public HealthController(EntityManagerFactory emf,
                             RedisCommands<String, String> redisCommands) {
        this.emf = emf;
        this.redisCommands = redisCommands;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        boolean dbUp;
        try {
            var em = emf.createEntityManager();
            em.createNativeQuery("SELECT 1").getSingleResult();
            em.close();
            dbUp = true;
        } catch (Exception e) {
            dbUp = false;
        }

        boolean redisUp;
        try {
            redisUp = "PONG".equals(redisCommands.ping());
        } catch (Exception e) {
            redisUp = false;
        }

        return ApiResponse.ok(Map.of(
                "status", dbUp && redisUp ? "UP" : "DEGRADED",
                "database", dbUp ? "connected" : "unreachable",
                "redis", redisUp ? "connected" : "unreachable",
                "emfOpen", emf.isOpen()
        ));
    }

    @GetMapping("/cache/stats")
    public ApiResponse<Map<String, Object>> cacheStats() {
        try {
            Long dbSize = redisCommands.dbsize();
            String info = redisCommands.info("stats");
            long hits = extractInfoLong(info, "keyspace_hits");
            long misses = extractInfoLong(info, "keyspace_misses");

            return ApiResponse.ok(Map.of(
                    "redis", Map.of(
                            "connected", true,
                            "dbSize", dbSize,
                            "keyspaceHits", hits,
                            "keyspaceMisses", misses
                    )
            ));
        } catch (Exception e) {
            return ApiResponse.ok(Map.of(
                    "redis", Map.of(
                            "connected", false,
                            "error", e.getMessage()
                    )
            ));
        }
    }

    private long extractInfoLong(String info, String key) {
        for (String line : info.split("\r?\n")) {
            if (line.startsWith(key + ":")) {
                return Long.parseLong(line.substring(key.length() + 1).trim());
            }
        }
        return 0;
    }
}
