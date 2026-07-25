package com.power.posval.kafka;

import com.power.posval.domain.event.PositionCaptured;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;
import com.power.posval.domain.service.SettlementMaterializationJob;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Kafka consumer for PositionCaptured events.
 * Triggers S3/S5/S6 cascade. Pattern #26, FR-106.
 */
public class TradeCapturedConsumer extends IdempotentConsumer<PositionCaptured> {

    private final VolumeSeriesRepository seriesRepo;
    private final PositionLedgerRepository ledgerRepo;
    private final SettlementMaterializationJob settlementJob;

    @Inject
    public TradeCapturedConsumer(VolumeSeriesRepository seriesRepo,
                                  PositionLedgerRepository ledgerRepo,
                                  SettlementMaterializationJob settlementJob) {
        this.seriesRepo = seriesRepo;
        this.ledgerRepo = ledgerRepo;
        this.settlementJob = settlementJob;
    }

    @Override
    protected boolean alreadyProcessed(PositionCaptured event) {
        // D-7: natural key (tradeId, tradeVersion) for idempotency
        return seriesRepo.existsByTradeIdAndTradeVersion(
            event.tradeId(), event.tradeVersion());
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
