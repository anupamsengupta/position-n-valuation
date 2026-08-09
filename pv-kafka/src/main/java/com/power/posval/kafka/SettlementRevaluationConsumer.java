package com.power.posval.kafka;

import com.power.posval.domain.event.SettlementRevaluationRequested;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import com.power.posval.domain.service.SettlementRevaluationService;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Kafka consumer for SettlementRevaluationRequested events.
 * Loads the position by ID and delegates to SettlementRevaluationService
 * for interval-level delete-then-insert revaluation.
 *
 * <p>Idempotent: re-revaluation produces the same cells (delete + re-insert).
 */
public class SettlementRevaluationConsumer extends IdempotentConsumer<SettlementRevaluationRequested> {

    private final PositionLedgerRepository ledgerRepo;
    private final SettlementRevaluationService revaluationService;

    @Inject
    public SettlementRevaluationConsumer(PositionLedgerRepository ledgerRepo,
                                          SettlementRevaluationService revaluationService) {
        this.ledgerRepo = ledgerRepo;
        this.revaluationService = revaluationService;
    }

    @Override
    protected boolean alreadyProcessed(SettlementRevaluationRequested event) {
        // Revaluation is idempotent (delete + re-insert), no dedup needed.
        return false;
    }

    @Override
    protected void process(SettlementRevaluationRequested event) {
        Optional<PositionLedgerEntry> entryOpt = ledgerRepo.findById(event.positionId());
        if (entryOpt.isEmpty()) {
            return; // Position may have been cancelled or superseded
        }
        revaluationService.revalue(entryOpt.get(), event.intervalStart(), event.intervalEnd());
    }
}
