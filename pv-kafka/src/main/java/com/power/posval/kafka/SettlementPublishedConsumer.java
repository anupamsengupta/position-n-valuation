package com.power.posval.kafka;

import com.power.posval.domain.event.SettlementComputed;
import com.power.posval.domain.service.RollupMaterializationService;
import jakarta.inject.Inject;

/**
 * Kafka consumer for SettlementComputed events.
 * Triggers S7 rollup materialization. Pattern #26.
 */
public class SettlementPublishedConsumer extends IdempotentConsumer<SettlementComputed> {

    private final RollupMaterializationService rollupService;

    @Inject
    public SettlementPublishedConsumer(RollupMaterializationService rollupService) {
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
                event.tenantId(),
                event.positionId(),
                event.intervalStart().toInstant(),
                event.intervalEnd().toInstant());
            // Materialize trade-leg rollup alongside portfolio rollup (S8.2)
            rollupService.materializeTradeLegRollup(
                event.tenantId(),
                event.positionId(),
                event.intervalStart().toInstant(),
                event.intervalEnd().toInstant());
        }
    }
}
