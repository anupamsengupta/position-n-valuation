package com.power.posval.kafka;

import com.power.posval.domain.event.SettlementComputed;
import com.power.posval.domain.model.TimeGranularity;
import com.power.posval.domain.port.repository.RollupRepository;
import com.power.posval.domain.service.RollupMaterializationService;
import jakarta.inject.Inject;

/**
 * Kafka consumer for SettlementComputed events.
 * Triggers S7 rollup materialization and refresh. Pattern #26.
 */
public class SettlementPublishedConsumer extends IdempotentConsumer<SettlementComputed> {

    private final RollupRepository rollupRepo;
    private final RollupMaterializationService rollupService;

    @Inject
    public SettlementPublishedConsumer(RollupRepository rollupRepo,
                                        RollupMaterializationService rollupService) {
        this.rollupRepo = rollupRepo;
        this.rollupService = rollupService;
    }

    @Override
    protected boolean alreadyProcessed(SettlementComputed event) {
        // D-7: re-derive-from-source — settlement recomputation is idempotent.
        return false;
    }

    @Override
    protected void process(SettlementComputed event) {
        // Materialize rollup from settlement cells for the affected position
        if (event.positionId() != null) {
            rollupService.materializeForPosition(
                null, // tenantId from event context — not carried on SettlementComputed
                event.positionId(),
                event.intervalStart().toInstant(),
                event.intervalEnd().toInstant());
        }

        // Also trigger the materialized view refresh (S7 batch path)
        rollupRepo.refresh(
            null,
            event.intervalStart().toInstant(),
            event.intervalEnd().toInstant(),
            TimeGranularity.MIN_15);
    }
}
