package com.power.posval.app.controller;

import com.power.posval.app.event.DashboardDataChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE endpoint for real-time dashboard update notifications.
 *
 * <p>Clients connect via {@code GET /api/dashboard/events?tenantId=...} and receive
 * named events whenever Kafka listeners finish processing domain events that
 * affect dashboard data.
 *
 * <p>Simulator-scope only (pv-app). The production host would use its own
 * tenant-aware push mechanism.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardSseController {

    private static final Logger log = LoggerFactory.getLogger(DashboardSseController.class);

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emittersByTenant =
            new ConcurrentHashMap<>();

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@RequestParam String tenantId) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout — browser reconnects via EventSource

        CopyOnWriteArrayList<SseEmitter> tenantEmitters =
                emittersByTenant.computeIfAbsent(tenantId, k -> new CopyOnWriteArrayList<>());
        tenantEmitters.add(emitter);

        Runnable cleanup = () -> tenantEmitters.remove(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        // Send initial connected event
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data("{\"status\":\"connected\"}"));
        } catch (IOException e) {
            tenantEmitters.remove(emitter);
        }

        log.debug("SSE client subscribed for tenant={}, active={}", tenantId, tenantEmitters.size());
        return emitter;
    }

    @EventListener
    public void onDashboardDataChanged(DashboardDataChangedEvent event) {
        CopyOnWriteArrayList<SseEmitter> tenantEmitters = emittersByTenant.get(event.getTenantId());
        if (tenantEmitters == null || tenantEmitters.isEmpty()) {
            return;
        }

        String json = String.format(
                "{\"changeType\":\"%s\",\"portfolioId\":%s,\"eventTime\":\"%s\"}",
                event.getChangeType(),
                event.getPortfolioId() != null ? "\"" + event.getPortfolioId() + "\"" : "null",
                event.getEventTime()
        );

        for (SseEmitter emitter : tenantEmitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("dashboard-update")
                        .data(json));
            } catch (IOException e) {
                tenantEmitters.remove(emitter);
            }
        }
    }

    /**
     * Heartbeat to keep SSE connections alive through proxies/load balancers.
     */
    @Scheduled(fixedRate = 30_000)
    public void sendHeartbeat() {
        for (Map.Entry<String, CopyOnWriteArrayList<SseEmitter>> entry : emittersByTenant.entrySet()) {
            CopyOnWriteArrayList<SseEmitter> tenantEmitters = entry.getValue();
            for (SseEmitter emitter : tenantEmitters) {
                try {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                } catch (IOException e) {
                    tenantEmitters.remove(emitter);
                }
            }
            // Clean up empty tenant lists
            if (tenantEmitters.isEmpty()) {
                emittersByTenant.remove(entry.getKey(), tenantEmitters);
            }
        }
    }
}
