package com.power.posval.domain.service.stub;

import com.power.posval.domain.port.service.ForwardMarkService;
import com.power.posval.domain.port.service.IntervalMark;
import com.power.posval.domain.port.service.MonthlyMark;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Stub implementation of {@link ForwardMarkService} for the simulator (pv-app)
 * and unit tests.
 *
 * <p>Returns zero marks for all queries. This stub exists because the real
 * implementation depends on ADR-002 infrastructure (Redis-cached S4 curve
 * evaluation × S6b volumes) that does not yet exist (OI-1).
 *
 * <p>Bound in {@link com.power.posval.guice.StubServiceModule}.
 * Replace with the real implementation when ADR-002 is delivered.
 *
 * <p>D-13: no Spring types. D-14: stub lives in pv-domain but is only
 * bound via {@code StubServiceModule} which is installed by the simulator.
 */
public class StubForwardMarkService implements ForwardMarkService {

    @Override
    public MonthlyMark computeMonthlyMark(String tenantId, UUID positionId,
                                           Instant monthStart, Instant monthEnd) {
        return new MonthlyMark(
            positionId,
            monthStart,
            monthEnd,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            0L,
            0L,
            "EUR"
        );
    }

    @Override
    public List<IntervalMark> computeIntervalMarks(String tenantId, UUID positionId,
                                                    Instant dayStart, Instant dayEnd) {
        return List.of();
    }

    @Override
    public BigDecimal computePortfolioMtm(String tenantId, String portfolioId,
                                           Instant periodStart, Instant periodEnd) {
        return BigDecimal.ZERO;
    }
}
