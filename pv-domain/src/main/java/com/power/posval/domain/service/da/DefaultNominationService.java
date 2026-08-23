package com.power.posval.domain.service.da;

import com.power.posval.domain.command.NominationInterval;
import com.power.posval.domain.command.RecordNomination;
import com.power.posval.domain.event.NominationDeviationDetected;
import com.power.posval.domain.event.NominationRecorded;
import com.power.posval.domain.model.AlertCategory;
import com.power.posval.domain.model.AlertSeverity;
import com.power.posval.domain.model.AlertStatus;
import com.power.posval.domain.model.NominationRecord;
import com.power.posval.domain.model.OperationalAlert;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.repository.NominationRepository;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import com.power.posval.domain.port.service.NominationService;
import com.power.posval.domain.port.service.OperationalAlertService;
import com.power.posval.domain.model.value.NominationDeviation;

import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Default implementation of {@link NominationService}.
 *
 * <p>Stores per-interval nomination volumes for a balancing group and compares them
 * against traded volumes in the position ledger to detect scheduling deviations
 * (DA-VOL-03, FR-021, A-4).
 *
 * <p>Deviation tolerance: any non-zero difference triggers an alert and an event.
 * Future configuration may add a configurable tolerance threshold; for v1 any deviation
 * is reported.
 *
 * <p>Nomination version is determined by incrementing the current maximum version found
 * for each {@code (tenantId, balancingGroupId, intervalStart)} tuple. For the first
 * submission per interval, version = 1.
 *
 * <p>D-13 compliant: no Spring imports. Uses {@code @jakarta.inject.Inject}.
 * Pattern #18, DA-VOL-03.
 */
public class DefaultNominationService implements NominationService {

    /** Central European zone for delivery day boundary computation. */
    private static final ZoneId CET = ZoneId.of("Europe/Berlin");

    private final NominationRepository nominationRepository;
    private final PositionLedgerRepository ledgerRepository;
    private final OperationalAlertService alertService;
    private final DomainEventPublisher eventPublisher;

    @Inject
    public DefaultNominationService(NominationRepository nominationRepository,
                                    PositionLedgerRepository ledgerRepository,
                                    OperationalAlertService alertService,
                                    DomainEventPublisher eventPublisher) {
        this.nominationRepository = Objects.requireNonNull(nominationRepository, "nominationRepository");
        this.ledgerRepository     = Objects.requireNonNull(ledgerRepository,     "ledgerRepository");
        this.alertService         = Objects.requireNonNull(alertService,          "alertService");
        this.eventPublisher       = Objects.requireNonNull(eventPublisher,        "eventPublisher");
    }

    /**
     * Record nomination intervals for a balancing group on a delivery day (DA-VOL-03).
     *
     * <p>Steps:
     * <ol>
     *   <li>For each interval in the command, determine the next nomination version
     *       by incrementing the current maximum version found in the repository.</li>
     *   <li>Build and persist {@link NominationRecord} rows.</li>
     *   <li>Compare persisted nominations against traded volumes from the ledger.</li>
     *   <li>For each deviating interval: publish {@link NominationDeviationDetected} and
     *       raise an {@code OperationalAlert(WARNING, NOMINATION_SCHEDULING)}.</li>
     *   <li>Publish {@link NominationRecorded}.</li>
     * </ol>
     *
     * <p>FR-021: nominations are a parallel tracking layer, NOT position origins.
     */
    @Override
    public List<NominationRecord> recordNomination(RecordNomination command) {
        Objects.requireNonNull(command, "command");

        String tenantId         = command.tenantId();
        String balancingGroupId = command.balancingGroupId();
        LocalDate deliveryDay   = command.deliveryDay();

        // Build nomination records with auto-incremented versions
        List<NominationRecord> records = new ArrayList<>();
        for (NominationInterval interval : command.intervals()) {
            // Determine next version: current max + 1, or 1 if no prior record
            int nextVersion = nominationRepository
                .findLatestByInterval(tenantId, balancingGroupId, interval.intervalStart())
                .map(r -> r.nominationVersion() + 1)
                .orElse(1);

            records.add(NominationRecord.builder()
                .nominationId(UUID.randomUUID())
                .tenantId(tenantId)
                .balancingGroupId(balancingGroupId)
                .deliveryDay(deliveryDay)
                .intervalStart(interval.intervalStart())
                .intervalEnd(interval.intervalEnd())
                .nominatedVolumeMw(interval.volumeMw())
                .nominationTimestamp(command.nominationTimestamp())
                .nominationVersion(nextVersion)
                .submittedBy(command.submittedBy())
                .build());
        }

        // Persist all records (adapter uses batched inserts; default falls back to saveAll)
        nominationRepository.saveAll(records);

        // Compare with traded volumes and raise alerts for deviations
        List<NominationDeviation> deviations =
            compareWithTraded(tenantId, balancingGroupId, deliveryDay);
        Instant now = Instant.now();
        for (NominationDeviation deviation : deviations) {
            // Publish one event per deviating interval (Pattern #24)
            eventPublisher.publish(new NominationDeviationDetected(
                tenantId,
                balancingGroupId,
                deliveryDay,
                deviation.intervalStart(),
                deviation.tradedMw(),
                deviation.nominatedMw(),
                now
            ));

            // Raise a WARNING operational alert for each deviating interval
            alertService.raise(OperationalAlert.builder()
                .alertId(UUID.randomUUID())
                .tenantId(tenantId)
                .category(AlertCategory.NOMINATION_SCHEDULING)
                .severity(AlertSeverity.WARNING)
                .alertType("NOMINATION_DEVIATION")
                .message("Nomination deviation detected for balancing group [" + balancingGroupId
                    + "] at interval " + deviation.intervalStart()
                    + ": traded=" + deviation.tradedMw() + " MW, nominated="
                    + deviation.nominatedMw() + " MW, deviation=" + deviation.deviationMw() + " MW")
                .deliveryDay(deliveryDay)
                .sourceEventId(tenantId + ":" + balancingGroupId + ":" + deviation.intervalStart())
                .raisedAt(now)
                .status(AlertStatus.OPEN)
                .build());
        }

        // Publish NominationRecorded event (Pattern #24)
        eventPublisher.publish(new NominationRecorded(
            tenantId, balancingGroupId, deliveryDay, records.size(), now
        ));

        return List.copyOf(records);
    }

    /**
     * Compare the latest nomination for each interval on a delivery day against the
     * traded volume aggregated from the position ledger.
     *
     * <p>Traded volume per interval is the algebraic sum of all current-knowledge
     * position quantities whose {@code deliveryStart} equals the interval start.
     * Any non-zero deviation is returned as a {@link NominationDeviation}.
     *
     * <p>DA-VOL-03.
     */
    @Override
    public List<NominationDeviation> compareWithTraded(String tenantId,
                                                        String balancingGroupId,
                                                        LocalDate deliveryDay) {
        Objects.requireNonNull(tenantId,         "tenantId");
        Objects.requireNonNull(balancingGroupId, "balancingGroupId");
        Objects.requireNonNull(deliveryDay,      "deliveryDay");

        // Load all nominations for the day (latest version per interval via repository order)
        List<NominationRecord> nominations =
            nominationRepository.findByDeliveryDay(tenantId, balancingGroupId, deliveryDay);

        if (nominations.isEmpty()) {
            return List.of();
        }

        // Delivery day boundaries in UTC (via CET midnight)
        Instant dayStart = deliveryDay.atStartOfDay(CET).toInstant();
        Instant dayEnd   = deliveryDay.plusDays(1).atStartOfDay(CET).toInstant();

        // Load all current positions for the tenant on the delivery day
        List<PositionLedgerEntry> positions =
            ledgerRepository.findAllByDeliveryRange(tenantId, dayStart, dayEnd);

        // Aggregate traded MW by interval start (signed sum of all positions)
        Map<Instant, BigDecimal> tradedByInterval = new HashMap<>();
        for (PositionLedgerEntry pos : positions) {
            tradedByInterval.merge(pos.deliveryStart(), pos.quantity(), BigDecimal::add);
        }

        // Produce deviations for intervals where nominated != traded
        List<NominationDeviation> deviations = new ArrayList<>();
        // Process only the latest nomination version per interval
        Map<Instant, NominationRecord> latestByInterval = new HashMap<>();
        for (NominationRecord nom : nominations) {
            latestByInterval.merge(nom.intervalStart(), nom,
                (a, b) -> a.nominationVersion() >= b.nominationVersion() ? a : b);
        }

        for (Map.Entry<Instant, NominationRecord> entry : latestByInterval.entrySet()) {
            Instant intervalStart = entry.getKey();
            NominationRecord nom  = entry.getValue();
            BigDecimal traded = tradedByInterval.getOrDefault(intervalStart, BigDecimal.ZERO);
            BigDecimal nominated = nom.nominatedVolumeMw();
            BigDecimal deviation = nominated.subtract(traded);
            if (deviation.compareTo(BigDecimal.ZERO) != 0) {
                deviations.add(new NominationDeviation(intervalStart, traded, nominated, deviation));
            }
        }

        return List.copyOf(deviations);
    }
}
