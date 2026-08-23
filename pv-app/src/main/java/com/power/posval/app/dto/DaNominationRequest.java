package com.power.posval.app.dto;

import com.power.posval.domain.command.NominationInterval;
import com.power.posval.domain.command.RecordNomination;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * REST request DTO for {@code POST /api/da/nominations}.
 *
 * <p>Carries the set of per-interval nominations for a balancing group on a given
 * delivery day. Converted to a {@link RecordNomination} domain command.
 * Simulator-scope (pv-app). S9.4, DA-VOL-03.
 */
public record DaNominationRequest(
    String tenantId,
    String balancingGroupId,
    String deliveryDay,
    List<IntervalDto> intervals,
    String nominationTimestamp,
    String submittedBy
) {

    /**
     * A single nomination interval within the request.
     */
    public record IntervalDto(
        String intervalStart,
        String intervalEnd,
        String volumeMw
    ) {}

    /** Convert to the {@link RecordNomination} domain command. */
    public RecordNomination toCommand() {
        List<NominationInterval> domainIntervals = intervals.stream()
            .map(i -> new NominationInterval(
                Instant.parse(i.intervalStart()),
                Instant.parse(i.intervalEnd()),
                new BigDecimal(i.volumeMw())
            ))
            .toList();

        return new RecordNomination(
            tenantId,
            balancingGroupId,
            LocalDate.parse(deliveryDay),
            domainIntervals,
            Instant.parse(nominationTimestamp),
            submittedBy
        );
    }
}
