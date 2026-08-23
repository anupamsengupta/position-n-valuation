package com.power.posval.domain.port.service;

import com.power.posval.domain.command.RecordNomination;
import com.power.posval.domain.model.NominationRecord;
import com.power.posval.domain.model.value.NominationDeviation;

import java.time.LocalDate;
import java.util.List;

/**
 * Service port for per-interval nomination volume management (DA-VOL-03).
 *
 * <p>Nominations are parallel to, but independent from, positions. A nomination is the
 * volume submitted to the TSO for physical scheduling. The service detects deviations
 * between nomination and traded volume and raises alerts when deviations are detected
 * (FR-021, A-4).
 *
 * <p>Pattern #18 (Service Port), S5.2.
 */
public interface NominationService {

    /**
     * Record nomination intervals for a balancing group on a delivery day.
     *
     * <p>Steps:
     * <ol>
     *   <li>Persist {@link NominationRecord} rows for each interval in the command.</li>
     *   <li>Compare with traded volumes from the position ledger.</li>
     *   <li>If any interval has a deviation: publish {@code NominationDeviationDetected}
     *       via the outbox and raise an {@code OperationalAlert(WARNING, NOMINATION_SCHEDULING)}.</li>
     *   <li>Publish {@code NominationRecorded} via the outbox.</li>
     * </ol>
     *
     * @param command the nomination command; must not be null
     * @return the persisted nomination records; never null; ordered by interval start
     */
    List<NominationRecord> recordNomination(RecordNomination command);

    /**
     * Compare the latest nomination for each interval in a delivery day against the
     * traded volume in the position ledger. Returns a deviation record for every interval
     * where {@code nominatedMw != tradedMw} beyond the configured tolerance.
     *
     * @param tenantId         tenant identifier (D-14, Pattern #32)
     * @param balancingGroupId balancing group identifier
     * @param deliveryDay      CET-interpreted delivery day
     * @return list of deviation records; empty list if all intervals are in balance
     */
    List<NominationDeviation> compareWithTraded(String tenantId, String balancingGroupId,
                                                 LocalDate deliveryDay);
}
