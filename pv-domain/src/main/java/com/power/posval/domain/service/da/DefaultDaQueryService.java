package com.power.posval.domain.service.da;

import com.power.posval.domain.command.ComputeExchangeFees;
import com.power.posval.domain.model.BalancingGroup;
import com.power.posval.domain.model.ImbalanceRecord;
import com.power.posval.domain.model.NominationRecord;
import com.power.posval.domain.model.OperationalAlert;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.SettlementCell;
import com.power.posval.domain.model.value.ExchangeFeeResult;
import com.power.posval.domain.model.value.ImbalanceMonthSummary;
import com.power.posval.domain.port.repository.BalancingGroupRepository;
import com.power.posval.domain.port.repository.ImbalanceRecordRepository;
import com.power.posval.domain.port.repository.NominationRepository;
import com.power.posval.domain.port.repository.OperationalAlertRepository;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import com.power.posval.domain.port.repository.SettlementCellRepository;
import com.power.posval.domain.port.service.DaQueryService;
import com.power.posval.domain.port.service.ExchangeFeeService;
import com.power.posval.domain.port.service.ImbalanceSettlementService;

import jakarta.inject.Inject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Default read-side implementation of {@link DaQueryService}.
 *
 * <p>All methods are read-only and compose data from existing repository ports
 * without introducing new persistence side effects. The fee query delegates to
 * {@link ExchangeFeeService#computeForDay} which is compute-on-read for the GET path;
 * for the GET endpoint this is safe because the service is idempotent (same inputs
 * always produce the same fee result given the same schedule data).
 *
 * <p>D-13: no Spring imports; constructor injection only; {@code @jakarta.inject.Inject}.
 * D-14: tenantId is always a parameter.
 * Pattern #18 (Service Port + Implementation), S16.2.2.
 */
public class DefaultDaQueryService implements DaQueryService {

    /** Default exchange used for fee lookups when no exchange is specified. DA-SET-03. */
    private static final String DEFAULT_EXCHANGE = "EPEX_SPOT";

    private final PositionLedgerRepository ledgerRepository;
    private final SettlementCellRepository settlementCellRepository;
    private final NominationRepository nominationRepository;
    private final BalancingGroupRepository balancingGroupRepository;
    private final ImbalanceRecordRepository imbalanceRecordRepository;
    private final ImbalanceSettlementService imbalanceSettlementService;
    private final ExchangeFeeService exchangeFeeService;
    private final OperationalAlertRepository alertRepository;

    @Inject
    public DefaultDaQueryService(
            PositionLedgerRepository ledgerRepository,
            SettlementCellRepository settlementCellRepository,
            NominationRepository nominationRepository,
            BalancingGroupRepository balancingGroupRepository,
            ImbalanceRecordRepository imbalanceRecordRepository,
            ImbalanceSettlementService imbalanceSettlementService,
            ExchangeFeeService exchangeFeeService,
            OperationalAlertRepository alertRepository) {
        this.ledgerRepository           = Objects.requireNonNull(ledgerRepository,           "ledgerRepository");
        this.settlementCellRepository   = Objects.requireNonNull(settlementCellRepository,   "settlementCellRepository");
        this.nominationRepository       = Objects.requireNonNull(nominationRepository,       "nominationRepository");
        this.balancingGroupRepository   = Objects.requireNonNull(balancingGroupRepository,   "balancingGroupRepository");
        this.imbalanceRecordRepository  = Objects.requireNonNull(imbalanceRecordRepository,  "imbalanceRecordRepository");
        this.imbalanceSettlementService = Objects.requireNonNull(imbalanceSettlementService, "imbalanceSettlementService");
        this.exchangeFeeService         = Objects.requireNonNull(exchangeFeeService,         "exchangeFeeService");
        this.alertRepository            = Objects.requireNonNull(alertRepository,            "alertRepository");
    }

    /**
     * Load settlement cells for all positions in a bidding zone on a delivery day.
     *
     * <p>Computes UTC delivery day boundaries from the CET-localised {@code deliveryDay}
     * and {@code timezone}, queries the position ledger filtered by {@code deliveryPointId},
     * then fetches settlement cells per position via
     * {@link SettlementCellRepository#findByPosition}.
     *
     * <p>DA-UI-02, S16.2.2.
     */
    @Override
    public List<SettlementCell> findSettlementCellsForDay(String tenantId,
                                                           LocalDate deliveryDay,
                                                           String biddingZone,
                                                           ZoneId timezone) {
        Objects.requireNonNull(tenantId,    "tenantId");
        Objects.requireNonNull(deliveryDay, "deliveryDay");
        Objects.requireNonNull(biddingZone, "biddingZone");
        Objects.requireNonNull(timezone,    "timezone");

        Instant dayStart = deliveryDay.atStartOfDay(timezone).toInstant();
        Instant dayEnd   = deliveryDay.plusDays(1).atStartOfDay(timezone).toInstant();

        List<PositionLedgerEntry> positions =
            ledgerRepository.findAllByDeliveryRange(tenantId, dayStart, dayEnd)
                .stream()
                .filter(p -> biddingZone.equals(p.deliveryPointId()))
                .toList();

        List<SettlementCell> cells = new ArrayList<>();
        for (PositionLedgerEntry pos : positions) {
            cells.addAll(settlementCellRepository.findByPosition(
                tenantId, pos.id(), dayStart, dayEnd));
        }
        // Sort by intervalStart ascending
        cells.sort(java.util.Comparator.comparing(SettlementCell::intervalStart));
        return List.copyOf(cells);
    }

    /**
     * Load position entries for a bidding zone on a delivery day.
     *
     * <p>DA-UI-02, S16.2.2.
     */
    @Override
    public List<PositionLedgerEntry> findPositionsForDay(String tenantId,
                                                          LocalDate deliveryDay,
                                                          String biddingZone,
                                                          ZoneId timezone) {
        Objects.requireNonNull(tenantId,    "tenantId");
        Objects.requireNonNull(deliveryDay, "deliveryDay");
        Objects.requireNonNull(biddingZone, "biddingZone");
        Objects.requireNonNull(timezone,    "timezone");

        Instant dayStart = deliveryDay.atStartOfDay(timezone).toInstant();
        Instant dayEnd   = deliveryDay.plusDays(1).atStartOfDay(timezone).toInstant();

        return ledgerRepository.findAllByDeliveryRange(tenantId, dayStart, dayEnd)
            .stream()
            .filter(p -> biddingZone.equals(p.deliveryPointId()))
            .sorted(java.util.Comparator.comparing(PositionLedgerEntry::deliveryStart))
            .toList();
    }

    /**
     * Load nomination records for a delivery day, optionally filtered by balancing group.
     *
     * <p>When {@code balancingGroupId} is {@code null}, loads nominations for all active
     * balancing groups and merges the results ordered by {@code intervalStart}. DA-UI-03.
     */
    @Override
    public List<NominationRecord> findNominationsForDay(String tenantId,
                                                         LocalDate deliveryDay,
                                                         String balancingGroupId) {
        Objects.requireNonNull(tenantId,    "tenantId");
        Objects.requireNonNull(deliveryDay, "deliveryDay");

        if (balancingGroupId != null) {
            return nominationRepository.findByDeliveryDay(tenantId, balancingGroupId, deliveryDay);
        }

        // Aggregate across all active balancing groups
        List<BalancingGroup> groups = balancingGroupRepository.findActive(tenantId);
        List<NominationRecord> all = new ArrayList<>();
        for (BalancingGroup bg : groups) {
            all.addAll(nominationRepository.findByDeliveryDay(
                tenantId, bg.bgId().toString(), deliveryDay));
        }
        all.sort(java.util.Comparator.comparing(NominationRecord::intervalStart));
        return List.copyOf(all);
    }

    /**
     * Load all active balancing groups for a tenant. DA-UI-03.
     */
    @Override
    public List<BalancingGroup> findActiveBalancingGroups(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        return balancingGroupRepository.findActive(tenantId);
    }

    /**
     * Load imbalance records for a delivery day and balancing group. DA-UI-04.
     */
    @Override
    public List<ImbalanceRecord> findImbalanceForDay(String tenantId,
                                                      LocalDate deliveryDay,
                                                      String balancingGroupId) {
        Objects.requireNonNull(tenantId,         "tenantId");
        Objects.requireNonNull(deliveryDay,      "deliveryDay");
        Objects.requireNonNull(balancingGroupId, "balancingGroupId");
        return imbalanceRecordRepository.findByDeliveryDay(tenantId, balancingGroupId, deliveryDay);
    }

    /**
     * Monthly imbalance aggregate for a balancing group. Delegates to
     * {@link ImbalanceSettlementService#monthlyAggregate}. DA-UI-04.
     */
    @Override
    public ImbalanceMonthSummary findImbalanceMonthly(String tenantId,
                                                       YearMonth yearMonth,
                                                       String balancingGroupId) {
        Objects.requireNonNull(tenantId,         "tenantId");
        Objects.requireNonNull(yearMonth,        "yearMonth");
        Objects.requireNonNull(balancingGroupId, "balancingGroupId");
        return imbalanceSettlementService.monthlyAggregate(tenantId, balancingGroupId, yearMonth);
    }

    /**
     * Compute fees for a delivery day via {@link ExchangeFeeService#computeForDay}.
     *
     * <p>This is compute-on-read: the fee computation is deterministic (same inputs,
     * same outputs) so calling it from a GET endpoint is safe. The service publishes
     * an outbox event; for the GET path this side effect is acceptable in the simulator
     * because fees are idempotent-safe (duplicate events are de-duplicated downstream).
     *
     * <p>DA-UI-05, S16.2.2.
     */
    @Override
    public ExchangeFeeResult findFeesForDay(String tenantId, LocalDate deliveryDay) {
        Objects.requireNonNull(tenantId,    "tenantId");
        Objects.requireNonNull(deliveryDay, "deliveryDay");
        return exchangeFeeService.computeForDay(
            new ComputeExchangeFees(tenantId, DEFAULT_EXCHANGE, deliveryDay));
    }

    /**
     * Retrieve open and acknowledged alerts for a tenant.
     *
     * <p>When {@code deliveryDay} is non-null the result is filtered to that day.
     * Otherwise returns all OPEN alerts (same contract as {@code getOpenAlerts}).
     * DA-UI-06, S16.2.2.
     */
    @Override
    public List<OperationalAlert> findAlerts(String tenantId, LocalDate deliveryDay) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (deliveryDay != null) {
            return alertRepository.findByDeliveryDay(tenantId, deliveryDay);
        }
        return alertRepository.findOpen(tenantId);
    }
}
