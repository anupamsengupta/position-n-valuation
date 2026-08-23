package com.power.posval.domain.port.service;

import com.power.posval.domain.model.BalancingGroup;
import com.power.posval.domain.model.ImbalanceRecord;
import com.power.posval.domain.model.NominationRecord;
import com.power.posval.domain.model.OperationalAlert;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.SettlementCell;
import com.power.posval.domain.model.value.ExchangeFeeResult;
import com.power.posval.domain.model.value.ImbalanceMonthSummary;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

/**
 * Read-side query service for DA (Day-Ahead) Exchange Spot dashboard views.
 *
 * <p>Aggregates data across multiple repositories to produce response shapes
 * required by the DA UI (S16.2.2, DA-UI-02 through DA-UI-07). All methods
 * are read-only and non-transactional.
 *
 * <p>D-13: no Spring annotations. D-14: tenantId is always a parameter.
 * Pattern #18 (Service Port), S16.2.2.
 */
public interface DaQueryService {

    /**
     * Load all settlement cells for positions in a given bidding zone on a delivery day.
     *
     * <p>Positions are looked up via {@code PositionLedgerRepository.findAllByDeliveryRange}
     * filtered to {@code deliveryPointId == biddingZone}. Settlement cells are fetched per
     * position and returned ordered by {@code intervalStart} ascending.
     *
     * <p>DA-UI-02, S16.2.2.
     *
     * @param tenantId    tenant identifier
     * @param deliveryDay the CET-interpreted delivery day
     * @param biddingZone bidding zone identifier (e.g. "DE_LU")
     * @param timezone    timezone for computing delivery day boundaries (default "Europe/Berlin")
     * @return settlement cells ordered by intervalStart; empty list if no data
     */
    List<SettlementCell> findSettlementCellsForDay(String tenantId, LocalDate deliveryDay,
                                                    String biddingZone, ZoneId timezone);

    /**
     * Load all position entries for a given bidding zone on a delivery day.
     *
     * <p>Used alongside {@link #findSettlementCellsForDay} to project per-trade info
     * (tradeId, tradeLegId, direction) onto the settlement grid rows.
     *
     * <p>DA-UI-02, S16.2.2.
     *
     * @param tenantId    tenant identifier
     * @param deliveryDay the CET-interpreted delivery day
     * @param biddingZone bidding zone identifier
     * @param timezone    timezone for computing delivery day boundaries
     * @return position entries ordered by deliveryStart; empty list if no data
     */
    List<PositionLedgerEntry> findPositionsForDay(String tenantId, LocalDate deliveryDay,
                                                   String biddingZone, ZoneId timezone);

    /**
     * Load all nomination records for a delivery day, optionally filtered to a balancing group.
     *
     * <p>When {@code balancingGroupId} is {@code null}, returns nominations across
     * all active balancing groups for the tenant. Ordered by intervalStart ascending.
     *
     * <p>DA-UI-03, S16.2.2.
     *
     * @param tenantId         tenant identifier
     * @param deliveryDay      the CET-interpreted delivery day
     * @param balancingGroupId optional filter; null means all groups
     * @return nomination records ordered by intervalStart; empty list if none
     */
    List<NominationRecord> findNominationsForDay(String tenantId, LocalDate deliveryDay,
                                                  String balancingGroupId);

    /**
     * Load all active balancing groups for a tenant. Used to populate dropdown filters.
     *
     * <p>DA-UI-03, S16.2.2.
     *
     * @param tenantId tenant identifier
     * @return active balancing groups ordered by bgCode; empty list if none
     */
    List<BalancingGroup> findActiveBalancingGroups(String tenantId);

    /**
     * Load all imbalance records for a delivery day and balancing group.
     *
     * <p>DA-UI-04, S16.2.2.
     *
     * @param tenantId         tenant identifier
     * @param deliveryDay      the CET-interpreted delivery day
     * @param balancingGroupId balancing group identifier
     * @return imbalance records ordered by intervalStart; empty list if none
     */
    List<ImbalanceRecord> findImbalanceForDay(String tenantId, LocalDate deliveryDay,
                                               String balancingGroupId);

    /**
     * Return the monthly imbalance summary for a balancing group.
     *
     * <p>Delegates to {@link ImbalanceSettlementService#monthlyAggregate}.
     *
     * <p>DA-UI-04, S16.2.2.
     *
     * @param tenantId         tenant identifier
     * @param yearMonth        the calendar month
     * @param balancingGroupId balancing group identifier
     * @return monthly imbalance summary; never null
     */
    ImbalanceMonthSummary findImbalanceMonthly(String tenantId, YearMonth yearMonth,
                                                String balancingGroupId);

    /**
     * Compute or retrieve exchange fees for a delivery day.
     *
     * <p>Delegates to {@link ExchangeFeeService#computeForDay} using exchange "EPEX_SPOT".
     * For the read endpoint this is a compute-on-read pattern — the fee is computed
     * and returned without persisting side effects (suitable for GET endpoints).
     *
     * <p>DA-UI-05, S16.2.2.
     *
     * @param tenantId    tenant identifier
     * @param deliveryDay the CET-interpreted delivery day
     * @return exchange fee result; never null
     */
    ExchangeFeeResult findFeesForDay(String tenantId, LocalDate deliveryDay);

    /**
     * Retrieve open and acknowledged alerts for a tenant, optionally filtered by delivery day.
     *
     * <p>DA-UI-06, S16.2.2.
     *
     * @param tenantId    tenant identifier
     * @param deliveryDay optional delivery day filter; null means all open/acknowledged alerts
     * @return operational alerts; empty list if none
     */
    List<OperationalAlert> findAlerts(String tenantId, LocalDate deliveryDay);
}
