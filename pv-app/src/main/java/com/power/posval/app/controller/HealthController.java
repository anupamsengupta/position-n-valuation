package com.power.posval.app.controller;

import com.power.posval.app.cache.InMemoryMarketDataCache;
import com.power.posval.app.cache.InMemoryVolumeCache;
import com.power.posval.app.dto.ApiResponse;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final EntityManagerFactory emf;
    private final InMemoryMarketDataCache marketDataCache;
    private final InMemoryVolumeCache volumeCache;

    public HealthController(EntityManagerFactory emf,
                             InMemoryMarketDataCache marketDataCache,
                             InMemoryVolumeCache volumeCache) {
        this.emf = emf;
        this.marketDataCache = marketDataCache;
        this.volumeCache = volumeCache;
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

        return ApiResponse.ok(Map.of(
                "status", dbUp ? "UP" : "DOWN",
                "database", dbUp ? "connected" : "unreachable",
                "emfOpen", emf.isOpen()
        ));
    }

    @GetMapping("/cache/stats")
    public ApiResponse<Map<String, Object>> cacheStats() {
        return ApiResponse.ok(Map.of(
                "marketData", Map.of(
                        "size", marketDataCache.size(),
                        "hits", marketDataCache.getHits(),
                        "misses", marketDataCache.getMisses()
                ),
                "volume", Map.of(
                        "size", volumeCache.size()
                )
        ));
    }
}
