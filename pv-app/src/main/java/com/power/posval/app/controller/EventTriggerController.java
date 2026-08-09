package com.power.posval.app.controller;

import com.power.posval.app.dto.ApiResponse;
import com.power.posval.app.dto.MarketDataUpdatedRequest;
import com.power.posval.app.dto.VolumeSupersededRequest;
import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.port.event.DomainEventPublisher;
import org.springframework.web.bind.annotation.*;

/**
 * REST endpoints for triggering domain events through the outbox → Kafka pipeline.
 *
 * <p>These endpoints allow testing the full production flow end-to-end via HTTP:
 * <ul>
 *   <li>Event is written to the outbox table (same as production)</li>
 *   <li>OutboxRelayProducer relays to Kafka topic</li>
 *   <li>Kafka listener receives and delegates to consumer</li>
 *   <li>Consumer performs cache invalidation, dependency lookup, revaluation</li>
 * </ul>
 *
 * <p>Not intended for production use — these events are normally produced by
 * internal domain operations (market data ingestion, volume series updates).
 */
@RestController
@RequestMapping("/api/events")
public class EventTriggerController {

    private final DomainEventPublisher eventPublisher;
    private final TransactionalExecutor txExecutor;

    public EventTriggerController(DomainEventPublisher eventPublisher,
                                    TransactionalExecutor txExecutor) {
        this.eventPublisher = eventPublisher;
        this.txExecutor = txExecutor;
    }

    /**
     * Trigger a MarketDataUpdated event through the outbox → Kafka pipeline.
     * Exercises: cache invalidation → S8 dependency index lookup → settlement revaluation.
     */
    @PostMapping("/market-data-updated")
    public ApiResponse<String> publishMarketDataUpdated(@RequestBody MarketDataUpdatedRequest request) {
        txExecutor.run(() -> eventPublisher.publish(request.toEvent()));
        return ApiResponse.ok("MarketDataUpdated event published to outbox",
                "Event will be relayed to posval.MarketDataUpdated topic");
    }

    /**
     * Trigger a VolumeSuperseded event through the outbox → Kafka pipeline.
     * Exercises: cache invalidation → position lookup by seriesKey → settlement revaluation.
     */
    @PostMapping("/volume-superseded")
    public ApiResponse<String> publishVolumeSuperseded(@RequestBody VolumeSupersededRequest request) {
        txExecutor.run(() -> eventPublisher.publish(request.toEvent()));
        return ApiResponse.ok("VolumeSuperseded event published to outbox",
                "Event will be relayed to posval.VolumeSuperseded topic");
    }
}
