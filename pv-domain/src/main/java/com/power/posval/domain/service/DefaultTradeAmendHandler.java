package com.power.posval.domain.service;

import com.power.posval.domain.command.TradeAmend;
import com.power.posval.domain.event.PositionAmended;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.repository.PositionLedgerRepository;

import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Domain service implementing TradeAmend.
 * FR-008: backdated corrections move knowledge time only;
 *         forward-effective changes move valid time.
 * FR-037: amendment carries both processing time and business-effective date.
 * Pattern #16, S1.
 */
public class DefaultTradeAmendHandler implements TradeAmendHandler {

    private final PositionLedgerRepository ledgerRepo;
    private final DomainEventPublisher eventPublisher;

    @Inject
    public DefaultTradeAmendHandler(PositionLedgerRepository ledgerRepo,
                                     DomainEventPublisher eventPublisher) {
        this.ledgerRepo = ledgerRepo;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public List<PositionLedgerEntry> handle(TradeAmend cmd) {
        // Find current entries for this trade-leg
        List<PositionLedgerEntry> currentEntries = ledgerRepo.findCurrentByTradeLeg(
            cmd.tenantId(), cmd.tradeId(), cmd.tradeLegId());

        // Build new versions with amended fields
        List<PositionLedgerEntry> newEntries = currentEntries.stream()
            .map(existing -> PositionLedgerEntry.builder()
                .id(UUID.randomUUID())
                .tenantId(cmd.tenantId())
                .tradeId(cmd.tradeId())
                .tradeLegId(cmd.tradeLegId())
                .tradeVersion(cmd.tradeVersion())
                .deliveryRange(existing.deliveryRange())
                .quantity(cmd.quantity() != null ? cmd.quantity() : existing.quantity())
                .volumeUnit(existing.volumeUnit())
                .priceExpressionId(cmd.priceExpressionId() != null
                    ? cmd.priceExpressionId() : existing.priceExpressionId())
                .portfolioId(cmd.portfolioId() != null ? cmd.portfolioId() : existing.portfolioId())
                .deliveryPointId(existing.deliveryPointId())
                .originType(existing.originType())
                .volumeSeriesKey(existing.volumeSeriesKey())
                .validFrom(cmd.businessEffectiveDate())
                .knownFrom(Instant.now())
                .status("ACTIVE")
                .amendmentReason(cmd.amendmentReason())
                .build())
            .toList();

        // FR-037: supersede old entries, save new ones
        ledgerRepo.supersede(currentEntries, newEntries);

        // Pattern #24: outbox write in same transaction
        eventPublisher.publish(new PositionAmended(
            cmd.tenantId(), cmd.tradeId(), cmd.tradeLegId(),
            cmd.tradeVersion(), cmd.amendmentReason(), Instant.now()));

        return newEntries;
    }
}
