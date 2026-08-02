package com.power.posval.kafka;

import com.power.posval.domain.event.CurveTick;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.port.repository.DependencyEdge;
import com.power.posval.domain.port.repository.DependencyIndex;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import com.power.posval.domain.service.ForwardMarkJob;
import jakarta.inject.Inject;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Kafka consumer for {@link CurveTick} events (market data curve updates).
 * Triggers S5b forward mark recalculation. Pattern #26.
 */
public class CurveTickConsumer extends IdempotentConsumer<CurveTick> {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private final DependencyIndex dependencyIndex;
    private final ForwardMarkJob forwardMarkJob;
    private final PositionLedgerRepository positionLedgerRepository;

    @Inject
    public CurveTickConsumer(DependencyIndex dependencyIndex,
                              ForwardMarkJob forwardMarkJob,
                              PositionLedgerRepository positionLedgerRepository) {
        this.dependencyIndex = dependencyIndex;
        this.forwardMarkJob = forwardMarkJob;
        this.positionLedgerRepository = positionLedgerRepository;
    }

    @Override
    protected boolean alreadyProcessed(CurveTick event) {
        // D-7: forward mark recalculation is idempotent (overwrites current state).
        return false;
    }

    @Override
    protected void process(CurveTick event) {
        if (event.affectedPillars() == null || event.affectedPillars().isEmpty()) {
            return;
        }

        YearMonth minPillar = Collections.min(event.affectedPillars());
        YearMonth maxPillar = Collections.max(event.affectedPillars());
        DeliveryRange range = new DeliveryRange(minPillar, maxPillar, UTC);

        List<DependencyEdge> affected = dependencyIndex.findAffectedCells(
            event.tenantId(), event.series(), range, null);

        for (DependencyEdge edge : affected) {
            Optional<PositionLedgerEntry> position =
                positionLedgerRepository.findById(edge.cellId());
            position.ifPresent(pos -> forwardMarkJob.execute(pos, range));
        }
    }
}
