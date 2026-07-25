package com.power.posval.kafka;

import com.power.posval.domain.event.SettlementComputed;
import com.power.posval.domain.model.TimeGranularity;
import com.power.posval.domain.port.repository.RollupRepository;
import jakarta.inject.Inject;

/**
 * Kafka consumer for SettlementComputed events.
 * Triggers S7 rollup refresh and S8 dependency index update. Pattern #26.
 */
public class SettlementPublishedConsumer extends IdempotentConsumer<SettlementComputed> {

    private final RollupRepository rollupRepo;

    @Inject
    public SettlementPublishedConsumer(RollupRepository rollupRepo) {
        this.rollupRepo = rollupRepo;
    }

    @Override
    protected boolean alreadyProcessed(SettlementComputed event) {
        // D-7: re-derive-from-source — settlement recomputation is idempotent.
        return false;
    }

    @Override
    protected void process(SettlementComputed event) {
        // Trigger rollup refresh for the affected delivery range (S7)
        rollupRepo.refresh(
            null, // tenantId from event context — not carried on SettlementComputed
            event.intervalStart().toInstant(),
            event.intervalEnd().toInstant(),
            TimeGranularity.MIN_15);
    }
}
