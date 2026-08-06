package com.power.posval.kafka;

import com.power.posval.domain.event.PositionCaptured;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import com.power.posval.domain.port.repository.SettlementCellRepository;
import com.power.posval.domain.service.SettlementMaterializationJob;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Kafka consumer for PositionCaptured events.
 * Triggers S3/S5/S6 cascade. Pattern #26, FR-106.
 */
public class TradeCapturedConsumer extends IdempotentConsumer<PositionCaptured> {

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
    protected boolean alreadyProcessed(PositionCaptured event) {
        // Check if settlement cells already exist for this trade's positions.
        // Find current ledger entries, then check if the first one has cells.
        List<PositionLedgerEntry> entries = ledgerRepo.findCurrentByTradeLeg(
            event.tenantId(), event.tradeId(), event.tradeLegId());

        if (entries.isEmpty()) {
            return false; // no entries → not processed yet
        }

        // If the first position already has settlement cells, this event was processed
        return cellRepo.existsByPositionId(event.tenantId(), entries.get(0).id());
    }

    @Override
    protected void process(PositionCaptured event) {
        // Retrieve position ledger entries for the captured trade
        List<PositionLedgerEntry> entries = ledgerRepo.findCurrentByTradeLeg(
            event.tenantId(), event.tradeId(), event.tradeLegId());

        // Trigger settlement materialization (S5a) for each position entry
        for (PositionLedgerEntry entry : entries) {
            settlementJob.execute(entry, entry.deliveryRange());
        }
    }
}
