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
 *
 * <p>Idempotent: checks if settlement cells already exist for this
 * (tradeId, tradeLegId, tradeVersion) before processing.
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
        // Find entries for this specific version (not all versions of the trade)
        List<PositionLedgerEntry> entries = ledgerRepo.findCurrentByTradeLegAndVersion(
            event.tenantId(), event.tradeId(), event.tradeLegId(), event.tradeVersion());

        if (entries.isEmpty()) {
            return false;
        }

        // If the first position of this version has settlement cells, already processed
        return cellRepo.existsByPositionId(event.tenantId(), entries.get(0).id());
    }

    @Override
    protected void process(PositionCaptured event) {
        // Retrieve entries for this specific version
        List<PositionLedgerEntry> entries = ledgerRepo.findCurrentByTradeLegAndVersion(
            event.tenantId(), event.tradeId(), event.tradeLegId(), event.tradeVersion());

        // Trigger settlement materialization (S5a) for each position entry
        for (PositionLedgerEntry entry : entries) {
            settlementJob.execute(entry, entry.deliveryRange());
        }
    }
}
