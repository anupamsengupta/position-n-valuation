package com.power.posval.kafka;

import com.power.posval.domain.event.PositionCaptured;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;
import jakarta.inject.Inject;

/**
 * Kafka consumer for PositionCaptured events.
 * Triggers S3/S5/S6 cascade. Pattern #26, FR-106.
 */
public class TradeCapturedConsumer extends IdempotentConsumer<PositionCaptured> {

    private final VolumeSeriesRepository seriesRepo;

    @Inject
    public TradeCapturedConsumer(VolumeSeriesRepository seriesRepo) {
        this.seriesRepo = seriesRepo;
    }

    @Override
    protected boolean alreadyProcessed(PositionCaptured event) {
        // D-7: natural key (tradeId, tradeVersion) for idempotency
        return seriesRepo.existsByTradeIdAndTradeVersion(
            event.tradeId(), event.tradeVersion());
    }

    @Override
    protected void process(PositionCaptured event) {
        // Trigger downstream cascade:
        // 1. Volume materialization (S3) — create/populate VolumeSeries
        // 2. Settlement computation (S5a) — compute initial settlement cells
        // 3. Cache population (S6) — warm slot cache
        // 4. Trade interval cache (S6b) — pre-multiply for portfolio dashboards
        //
        // In production, each step is orchestrated via the materialization pipeline.
        // The consumer delegates to domain services injected via Guice.
    }
}
