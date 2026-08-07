package com.power.posval.kafka;

import com.power.posval.domain.event.PositionEntryCaptured;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import com.power.posval.domain.port.repository.SettlementCellRepository;
import com.power.posval.domain.service.SettlementMaterializationJob;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Kafka consumer for PositionEntryCaptured events.
 * Each event carries a single positionId — no DB re-query by trade/leg/version needed.
 * Triggers S3/S5/S6 cascade. Pattern #26, FR-106.
 *
 * <p>Idempotent: checks if settlement cells already exist for this positionId.
 */
public class TradeCapturedConsumer extends IdempotentConsumer<PositionEntryCaptured> {

    private final PositionLedgerRepository ledgerRepo;
    private final SettlementCellRepository cellRepo;
    private final SettlementMaterializationJob settlementJob;

    @Inject
    public TradeCapturedConsumer(PositionLedgerRepository ledgerRepo,
                                  SettlementCellRepository cellRepo,
                                  SettlementMaterializationJob settlementJob) {
        this.ledgerRepo = ledgerRepo;
        this.cellRepo = cellRepo;
        this.settlementJob = settlementJob;
    }

    @Override
    protected boolean alreadyProcessed(PositionEntryCaptured event) {
        return cellRepo.existsByPositionId(event.tenantId(), event.positionId());
    }

    @Override
    protected void process(PositionEntryCaptured event) {
        Optional<PositionLedgerEntry> entryOpt = ledgerRepo.findById(event.positionId());
        if (entryOpt.isEmpty()) {
            throw new IllegalStateException(
                "PositionLedgerEntry not found for positionId=" + event.positionId());
        }
        PositionLedgerEntry entry = entryOpt.get();
        settlementJob.execute(entry, entry.deliveryRange());
    }
}
