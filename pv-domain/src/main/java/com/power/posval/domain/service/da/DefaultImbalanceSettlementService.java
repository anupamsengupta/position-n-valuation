package com.power.posval.domain.service.da;

import com.power.posval.domain.command.ComputeImbalanceSettlement;
import com.power.posval.domain.event.ImbalanceSettlementComputed;
import com.power.posval.domain.model.ImbalanceRecord;
import com.power.posval.domain.model.NominationRecord;
import com.power.posval.domain.port.NumericPrecision;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.marketdata.MarketDataPort;
import com.power.posval.domain.port.repository.ImbalanceRecordRepository;
import com.power.posval.domain.port.repository.NominationRepository;
import com.power.posval.domain.port.service.ImbalanceSettlementService;
import com.power.posval.domain.model.value.ImbalanceDaySummary;
import com.power.posval.domain.model.value.ImbalanceMonthSummary;

import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Default implementation of {@link ImbalanceSettlementService}.
 *
 * <p>For each 15-min delivery interval on a given day, computes the TSO reBAP imbalance
 * settlement amount (DA-SET-04, S8.3, A-5):
 * <pre>
 *   imbalanceVolumeMw  = actualDeliveredMw - nominatedVolumeMw
 *   imbalanceEnergyMwh = imbalanceVolumeMw * (intervalDurationSeconds / 3600)   [ENERGY scale 8]
 *   imbalanceAmount    = imbalanceEnergyMwh * reBAP                              [MONETARY scale 4]
 * </pre>
 *
 * <p>Actual delivered volumes are loaded from {@link MarketDataPort} using the series key
 * {@code "ACTUAL_" + balancingGroupId} for each interval. reBAP prices are loaded from
 * {@link MarketDataPort} using the series key {@code "REBAP_" + tsoArea} where
 * {@code tsoArea} is derived from the balancing group configuration.
 *
 * <p>For v1, the TSO area is not resolved through a separate {@code BalancingGroupRepository}
 * call — the {@code balancingGroupId} is used directly as a suffix for the actual-volume
 * series key, consistent with the S4 market data series naming convention. The reBAP series
 * key uses the prefix {@code "REBAP_DE"} as a default for German TSO areas (A-8, OQ-12).
 * A future iteration can resolve the TSO area from the balancing group entity.
 *
 * <p>Intervals with no metered actual data ({@link MarketDataPort#lookupFixing} returns
 * a PROVISIONAL/zero value) are included with {@code actualDeliveredMw = 0} and
 * the imbalance equals the full nominated volume.
 *
 * <p>Publishes {@link ImbalanceSettlementComputed} via the outbox on success (Pattern #24).
 *
 * <p>D-13 compliant: no Spring imports. Uses {@code @jakarta.inject.Inject}.
 * Pattern #18, DA-SET-04.
 */
public class DefaultImbalanceSettlementService implements ImbalanceSettlementService {

    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);
    private static final String EUR = "EUR";
    private static final ZoneId CET = ZoneId.of("Europe/Berlin");

    private final NominationRepository nominationRepository;
    private final MarketDataPort marketDataPort;
    private final ImbalanceRecordRepository imbalanceRecordRepository;
    private final DomainEventPublisher eventPublisher;

    @Inject
    public DefaultImbalanceSettlementService(NominationRepository nominationRepository,
                                              MarketDataPort marketDataPort,
                                              ImbalanceRecordRepository imbalanceRecordRepository,
                                              DomainEventPublisher eventPublisher) {
        this.nominationRepository      = Objects.requireNonNull(nominationRepository,      "nominationRepository");
        this.marketDataPort            = Objects.requireNonNull(marketDataPort,            "marketDataPort");
        this.imbalanceRecordRepository = Objects.requireNonNull(imbalanceRecordRepository, "imbalanceRecordRepository");
        this.eventPublisher            = Objects.requireNonNull(eventPublisher,            "eventPublisher");
    }

    /**
     * Compute and persist imbalance records for all intervals on a delivery day (DA-SET-04).
     *
     * <p>Steps:
     * <ol>
     *   <li>Load latest nominations by interval for the delivery day.</li>
     *   <li>For each interval: load actual volume from MarketDataPort and reBAP price.</li>
     *   <li>Apply the imbalance formula: imbalanceVolume = actual - nominated.</li>
     *   <li>Save all ImbalanceRecords.</li>
     *   <li>Publish {@link ImbalanceSettlementComputed}.</li>
     * </ol>
     */
    @Override
    public List<ImbalanceRecord> computeForDay(ComputeImbalanceSettlement command) {
        Objects.requireNonNull(command, "command");

        String tenantId         = command.tenantId();
        String balancingGroupId = command.balancingGroupId();
        LocalDate deliveryDay   = command.deliveryDay();
        Instant now             = Instant.now();

        // Load nominations for the day (latest version per interval)
        List<NominationRecord> nominations =
            nominationRepository.findByDeliveryDay(tenantId, balancingGroupId, deliveryDay);

        // Deduplicate to latest version per interval
        Map<Instant, NominationRecord> latestByInterval = new HashMap<>();
        for (NominationRecord nom : nominations) {
            latestByInterval.merge(nom.intervalStart(), nom,
                (a, b) -> a.nominationVersion() >= b.nominationVersion() ? a : b);
        }

        // Market data series keys per S4 naming convention
        String actualSeriesKey = "ACTUAL_" + balancingGroupId;
        String rebapSeriesKey  = "REBAP_DE"; // Default German reBAP series (A-8)

        List<ImbalanceRecord> records = new ArrayList<>();

        for (Map.Entry<Instant, NominationRecord> entry : latestByInterval.entrySet()) {
            NominationRecord nom   = entry.getValue();
            Instant intervalStart  = nom.intervalStart();
            Instant intervalEnd    = nom.intervalEnd();

            // Load actual delivered volume from MarketDataPort
            BigDecimal actualMw = marketDataPort
                .lookupFixing(actualSeriesKey, intervalStart)
                .value();

            // Load reBAP price from MarketDataPort
            BigDecimal rebapPrice = marketDataPort
                .lookupFixing(rebapSeriesKey, intervalStart)
                .value();

            // Compute imbalance
            BigDecimal nominatedMw      = nom.nominatedVolumeMw();
            BigDecimal imbalanceVolumeMw = actualMw.subtract(nominatedMw);

            // Interval duration in hours (DST-safe via Instant arithmetic)
            Duration intervalDuration = Duration.between(intervalStart, intervalEnd);
            BigDecimal intervalHours = BigDecimal.valueOf(intervalDuration.getSeconds())
                .divide(SECONDS_PER_HOUR, NumericPrecision.Domain.INTERMEDIATE.ordinal() + 10,
                    java.math.RoundingMode.HALF_UP);

            BigDecimal imbalanceEnergyMwh = imbalanceVolumeMw.multiply(intervalHours)
                .setScale(8, java.math.RoundingMode.HALF_UP);

            BigDecimal imbalanceAmount = imbalanceEnergyMwh.multiply(rebapPrice)
                .setScale(4, java.math.RoundingMode.HALF_UP);

            records.add(ImbalanceRecord.builder()
                .recordId(UUID.randomUUID())
                .tenantId(tenantId)
                .balancingGroupId(balancingGroupId)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .nominatedVolumeMw(nominatedMw)
                .actualDeliveredMw(actualMw)
                .imbalanceVolumeMw(imbalanceVolumeMw)
                .imbalanceEnergyMwh(imbalanceEnergyMwh)
                .imbalancePricePerMwh(rebapPrice)
                .imbalanceAmount(imbalanceAmount)
                .currency(EUR)
                .tsoDataSource("MARKET_DATA_PORT")
                .tsoPublicationTimestamp(now)
                .recordVersion(1)
                .computedAt(now)
                .deliveryDay(deliveryDay)
                .build());
        }

        // Batch persist
        imbalanceRecordRepository.saveAll(records);

        // Aggregate for event
        BigDecimal netMwh = records.stream()
            .map(ImbalanceRecord::imbalanceEnergyMwh)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(8, java.math.RoundingMode.HALF_UP);
        BigDecimal netAmount = records.stream()
            .map(ImbalanceRecord::imbalanceAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(4, java.math.RoundingMode.HALF_UP);

        eventPublisher.publish(new ImbalanceSettlementComputed(
            tenantId, balancingGroupId, deliveryDay, netMwh, netAmount, now
        ));

        return List.copyOf(records);
    }

    /**
     * Aggregate all imbalance records for a balancing group across a calendar month.
     * Returns net MWh (algebraic sum) and net amount (algebraic sum) plus a per-day
     * breakdown ordered by delivery day (DA-SET-04).
     */
    @Override
    public ImbalanceMonthSummary monthlyAggregate(String tenantId,
                                                   String balancingGroupId,
                                                   YearMonth month) {
        Objects.requireNonNull(tenantId,         "tenantId");
        Objects.requireNonNull(balancingGroupId, "balancingGroupId");
        Objects.requireNonNull(month,            "month");

        List<ImbalanceRecord> records =
            imbalanceRecordRepository.findByMonth(tenantId, balancingGroupId, month);

        // Aggregate by delivery day
        Map<LocalDate, BigDecimal[]> dailyTotals = new java.util.TreeMap<>();
        for (ImbalanceRecord rec : records) {
            dailyTotals.computeIfAbsent(rec.deliveryDay(), k -> new BigDecimal[]{
                BigDecimal.ZERO, BigDecimal.ZERO
            });
            BigDecimal[] totals = dailyTotals.get(rec.deliveryDay());
            totals[0] = totals[0].add(rec.imbalanceEnergyMwh()); // MWh
            totals[1] = totals[1].add(rec.imbalanceAmount());     // Amount
        }

        List<ImbalanceDaySummary> dailyBreakdown = new ArrayList<>();
        BigDecimal netMwh    = BigDecimal.ZERO;
        BigDecimal netAmount = BigDecimal.ZERO;
        for (Map.Entry<LocalDate, BigDecimal[]> entry : dailyTotals.entrySet()) {
            BigDecimal dayMwh    = entry.getValue()[0].setScale(8, java.math.RoundingMode.HALF_UP);
            BigDecimal dayAmount = entry.getValue()[1].setScale(4, java.math.RoundingMode.HALF_UP);
            dailyBreakdown.add(new ImbalanceDaySummary(entry.getKey(), dayMwh, dayAmount));
            netMwh    = netMwh.add(dayMwh);
            netAmount = netAmount.add(dayAmount);
        }

        return new ImbalanceMonthSummary(
            month,
            balancingGroupId,
            netMwh.setScale(8, java.math.RoundingMode.HALF_UP),
            netAmount.setScale(4, java.math.RoundingMode.HALF_UP),
            EUR,
            List.copyOf(dailyBreakdown)
        );
    }
}
