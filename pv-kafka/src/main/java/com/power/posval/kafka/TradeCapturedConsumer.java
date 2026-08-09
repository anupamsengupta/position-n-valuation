package com.power.posval.kafka;

import com.power.posval.domain.event.PositionEntryCaptured;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.value.VolumeReference;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import com.power.posval.domain.port.repository.SettlementCellRepository;
import com.power.posval.domain.service.SettlementMaterializationJob;
import com.power.posval.domain.service.TradeIntervalCacheRebuilder;
import jakarta.inject.Inject;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Kafka consumer for PositionEntryCaptured events.
 * Each event carries a single positionId — no DB re-query by trade/leg/version needed.
 * Triggers S5a settlement materialization then S6b cache population. Pattern #26, FR-106.
 *
 * <p>Idempotent: checks if settlement cells already exist for this positionId.
 */
public class TradeCapturedConsumer extends IdempotentConsumer<PositionEntryCaptured> {

    private final PositionLedgerRepository ledgerRepo;
    private final SettlementCellRepository cellRepo;
    private final SettlementMaterializationJob settlementJob;
    private final TradeIntervalCacheRebuilder cacheRebuilder;

    @Inject
    public TradeCapturedConsumer(PositionLedgerRepository ledgerRepo,
                                  SettlementCellRepository cellRepo,
                                  SettlementMaterializationJob settlementJob,
                                  TradeIntervalCacheRebuilder cacheRebuilder) {
        this.ledgerRepo = ledgerRepo;
        this.cellRepo = cellRepo;
        this.settlementJob = settlementJob;
        this.cacheRebuilder = cacheRebuilder;
    }

    @Override
    protected boolean alreadyProcessed(PositionEntryCaptured event) {
        return cellRepo.existsByPositionId(event.tenantId(), event.positionId());
    }

    @Override
    protected void process(PositionEntryCaptured event) {
        // handle() already called alreadyProcessed(), so skip straight to load
        PositionLedgerEntry entry = ledgerRepo.findById(event.positionId())
            .orElseThrow(() -> new IllegalStateException(
                "PositionLedgerEntry not found for positionId=" + event.positionId()));
        materializeSettlement(entry);
        populateCache(entry);
    }

    /**
     * Check idempotency and load the position ledger entry.
     * Returns empty if already processed or entry not found.
     *
     * <p>This is separated so that a host can run the check + load in a
     * read-only transaction before forking S5a and S6b into parallel
     * write transactions.
     */
    public Optional<PositionLedgerEntry> prepareIfNeeded(PositionEntryCaptured event) {
        if (alreadyProcessed(event)) {
            return Optional.empty();
        }
        return ledgerRepo.findById(event.positionId());
    }

    /**
     * S5a: settlement materialization + S8 dependency upsert + SettlementComputed events.
     * Must run inside a transaction.
     */
    public void materializeSettlement(PositionLedgerEntry entry) {
        settlementJob.execute(entry, entry.deliveryRange());
    }

    /**
     * S6b: populate trade interval cache for this position's delivery range.
     * Must run inside a transaction. Cache is rebuildable (D-12).
     */
    public void populateCache(PositionLedgerEntry entry) {
        VolumeReference ref = VolumeReference.builder()
            .id(UUID.randomUUID())
            .tradeLegId(entry.tradeLegId())
            .tradeId(entry.tradeId())
            .tenantId(entry.tenantId())
            .multiplier(entry.multiplier())
            .volumeSeriesKey(entry.volumeSeriesKey())
            .effectiveFrom(ZonedDateTime.ofInstant(
                entry.deliveryStart(), entry.deliveryRange().deliveryTimezone()))
            .effectiveTo(ZonedDateTime.ofInstant(
                entry.deliveryEnd(), entry.deliveryRange().deliveryTimezone()))
            .build();
        cacheRebuilder.rebuildForTradeLeg(entry.tenantId(), ref,
            entry.deliveryStart(), entry.deliveryEnd(), entry.deliveryRange());
    }
}
