package com.power.posval.domain.service;

import com.power.posval.domain.command.TradeCapture;
import com.power.posval.domain.event.PositionCaptured;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.repository.PositionLedgerRepository;

import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Domain service implementing TradeCapture.
 * Uses port interfaces only — no JPA, no framework.
 * Pattern #16, FR-030, FR-032, S1.
 */
public class DefaultTradeCaptureHandler implements TradeCaptureHandler {

    private final PositionLedgerRepository ledgerRepo;
    private final DomainEventPublisher eventPublisher;

    @Inject
    public DefaultTradeCaptureHandler(PositionLedgerRepository ledgerRepo,
                                       DomainEventPublisher eventPublisher) {
        this.ledgerRepo = ledgerRepo;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public List<PositionLedgerEntry> handle(TradeCapture cmd) {
        // FR-030: decompose delivery period into monthly blocks
        List<DeliveryRange> monthBlocks = cmd.deliveryPeriod().toMonthBlocks();

        // Trade's exact delivery boundaries
        Instant tradeStart = cmd.deliveryPeriod().start().toInstant();
        Instant tradeEnd = cmd.deliveryPeriod().end().toInstant();

        List<PositionLedgerEntry> entries = monthBlocks.stream()
            .map(block -> {
                // Clamp exact delivery start/end to this month's boundaries
                Instant blockStart = block.startInstant().toInstant();
                Instant blockEnd = block.endInstant().toInstant();
                Instant effectiveStart = tradeStart.isAfter(blockStart) ? tradeStart : blockStart;
                Instant effectiveEnd = tradeEnd.isBefore(blockEnd) ? tradeEnd : blockEnd;

                return PositionLedgerEntry.builder()
                    .id(UUID.randomUUID())
                    .tenantId(cmd.tenantId())
                    .tradeId(cmd.tradeId())
                    .tradeLegId(cmd.tradeLegId())
                    .tradeVersion(cmd.tradeVersion())
                    .deliveryRange(block)
                    .deliveryStart(effectiveStart)
                    .deliveryEnd(effectiveEnd)
                    .quantity(cmd.quantity())
                    .volumeUnit(cmd.volumeUnit())
                    .priceExpressionId(cmd.priceExpressionId())
                    .portfolioId(cmd.portfolioId())
                    .deliveryPointId(cmd.deliveryPointId())
                    .originType(cmd.originType())
                    .volumeSeriesKey(cmd.volumeSeriesKey())
                    .multiplier(cmd.multiplier())
                    .validFrom(cmd.businessEffectiveDate())
                    .knownFrom(Instant.now())
                    .status("ACTIVE")
                    .build();
            })
            .toList();

        entries.forEach(ledgerRepo::save);

        // Pattern #24: outbox write in same transaction
        eventPublisher.publish(new PositionCaptured(
            cmd.tenantId(), cmd.tradeId(), cmd.tradeLegId(),
            cmd.tradeVersion(), entries.size(), Instant.now()));

        return entries;
    }
}
