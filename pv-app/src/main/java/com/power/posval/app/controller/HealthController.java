package com.power.posval.app.controller;

import com.power.posval.app.dto.ApiResponse;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final EntityManagerFactory emf;
    private final RedisCommands<String, String> redisCommands;

    public HealthController(EntityManagerFactory emf,
                             RedisCommands<String, String> redisCommands) {
        this.emf = emf;
        this.redisCommands = redisCommands;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        log.info("GET /api/health");
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

        String status = dbUp && redisUp ? "UP" : "DEGRADED";
        log.info("GET /api/health => status={} db={} redis={}", status, dbUp, redisUp);
        return ApiResponse.ok(Map.of(
                "status", status,
                "database", dbUp ? "connected" : "unreachable",
                "redis", redisUp ? "connected" : "unreachable",
                "emfOpen", emf.isOpen()
        ));
    }

    @GetMapping("/cache/stats")
    public ApiResponse<Map<String, Object>> cacheStats() {
        log.info("GET /api/cache/stats");
        try {
            Long dbSize = redisCommands.dbsize();
            String info = redisCommands.info("stats");
            long hits = extractInfoLong(info, "keyspace_hits");
            long misses = extractInfoLong(info, "keyspace_misses");

            log.info("GET /api/cache/stats => dbSize={} hits={} misses={}", dbSize, hits, misses);
            return ApiResponse.ok(Map.of(
                    "redis", Map.of(
                            "connected", true,
                            "dbSize", dbSize,
                            "keyspaceHits", hits,
                            "keyspaceMisses", misses
                    )
            ));
        } catch (Exception e) {
            log.info("GET /api/cache/stats => error: {}", e.getMessage());
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
