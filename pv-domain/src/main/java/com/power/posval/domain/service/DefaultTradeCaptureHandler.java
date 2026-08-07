package com.power.posval.domain.service;

import com.power.posval.domain.command.TradeCapture;
import com.power.posval.domain.event.PositionEntryCaptured;
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
 * Idempotent: duplicate captures for the same (tradeId, tradeLegId, tradeVersion)
 * return existing entries without creating duplicates or re-publishing events.
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
        // Idempotency: check if entries already exist for this (tradeId, tradeLegId, tradeVersion)
        List<PositionLedgerEntry> existing = ledgerRepo.findCurrentByTradeLegAndVersion(
            cmd.tenantId(), cmd.tradeId(), cmd.tradeLegId(), cmd.tradeVersion());
        if (!existing.isEmpty()) {
            return existing;
        }

        // FR-037: supersede previous version's entries (set known_to = now)
        List<PositionLedgerEntry> previousVersionEntries = ledgerRepo.findCurrentByTradeLeg(
            cmd.tenantId(), cmd.tradeId(), cmd.tradeLegId());

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
                    .marketPriceExpressionId(cmd.marketPriceExpressionId())
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

        if (!previousVersionEntries.isEmpty()) {
            // Bitemporal supersession: close old entries + persist new ones atomically
            ledgerRepo.supersede(previousVersionEntries, entries);
        } else {
            // First version: just persist
            entries.forEach(ledgerRepo::save);
        }

        // Pattern #24: one outbox row per entry — enables partition-level parallelism
        Instant now = Instant.now();
        entries.forEach(entry ->
            eventPublisher.publish(new PositionEntryCaptured(
                cmd.tenantId(), entry.id(), now)));

        return entries;
    }
}
