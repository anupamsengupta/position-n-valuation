package com.power.posval.domain.service;

import com.power.posval.domain.command.TradeCancel;
import com.power.posval.domain.event.PositionCancelled;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.repository.PositionLedgerRepository;

import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Domain service implementing TradeCancel.
 * FR-038: forward unwind closes valid_time; void-ab-initio creates CANCELLED version.
 * Pattern #16, S1.
 */
public class DefaultTradeCancelHandler implements TradeCancelHandler {

    private final PositionLedgerRepository ledgerRepo;
    private final DomainEventPublisher eventPublisher;

    @Inject
    public DefaultTradeCancelHandler(PositionLedgerRepository ledgerRepo,
                                      DomainEventPublisher eventPublisher) {
        this.ledgerRepo = ledgerRepo;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public List<PositionLedgerEntry> handle(TradeCancel cmd) {
        // Find current entries for this trade-leg
        List<PositionLedgerEntry> currentEntries = ledgerRepo.findCurrentByTradeLeg(
            cmd.tenantId(), cmd.tradeId(), cmd.tradeLegId());

        // Create cancelled versions
        List<PositionLedgerEntry> cancelledEntries = currentEntries.stream()
            .map(existing -> PositionLedgerEntry.builder()
                .id(UUID.randomUUID())
                .tenantId(cmd.tenantId())
                .tradeId(cmd.tradeId())
                .tradeLegId(cmd.tradeLegId())
                .tradeVersion(cmd.tradeVersion())
                .deliveryRange(existing.deliveryRange())
                .quantity(existing.quantity())
                .volumeUnit(existing.volumeUnit())
                .priceExpressionId(existing.priceExpressionId())
                .portfolioId(existing.portfolioId())
                .deliveryPointId(existing.deliveryPointId())
                .originType(existing.originType())
                .volumeSeriesKey(existing.volumeSeriesKey())
                .validFrom(cmd.businessEffectiveDate())
                .knownFrom(Instant.now())
                .status("CANCELLED")
                .build())
            .toList();

        // Close existing entries, save cancelled versions
        ledgerRepo.supersede(currentEntries, cancelledEntries);

        // Pattern #24: outbox write in same transaction
        eventPublisher.publish(new PositionCancelled(
            cmd.tenantId(), cmd.tradeId(), cmd.tradeLegId(),
            cmd.tradeVersion(), cmd.cancellationType(), Instant.now()));

        return cancelledEntries;
    }
}
