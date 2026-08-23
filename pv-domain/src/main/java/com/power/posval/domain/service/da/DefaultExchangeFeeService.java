package com.power.posval.domain.service.da;

import com.power.posval.domain.command.ComputeExchangeFees;
import com.power.posval.domain.event.ExchangeFeesComputed;
import com.power.posval.domain.model.ExchangeFeeSchedule;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.port.NumericPrecision;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.repository.ExchangeFeeScheduleRepository;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import com.power.posval.domain.port.service.ExchangeFeeService;
import com.power.posval.domain.model.value.ExchangeFeeResult;
import com.power.posval.domain.model.value.FeeLineItem;

import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Default implementation of {@link ExchangeFeeService}.
 *
 * <p>Computes exchange fees for all traded positions on a delivery day using the
 * effective fee schedule for the exchange and tenant (DA-SET-03, S8.4):
 * <pre>
 *   grossVolumeMwh = sum( abs(position.quantity) * intervalHours )
 *   feeAmount      = grossVolumeMwh * ratePerMwh          [MONETARY scale 4]
 * </pre>
 *
 * <p>Two fee types are processed: {@code TRADING} and {@code CLEARING}. For each type,
 * the effective rate is resolved via {@link ExchangeFeeScheduleRepository#findEffective}.
 * Fee types with no configured schedule are silently skipped (no error). Fee amounts
 * are rounded to MONETARY scale (4 decimal places) per {@link NumericPrecision}.
 *
 * <p>Publishes {@link ExchangeFeesComputed} via the outbox on success (Pattern #24).
 *
 * <p>D-13 compliant: no Spring imports. Uses {@code @jakarta.inject.Inject}.
 * Pattern #18, DA-SET-03.
 */
public class DefaultExchangeFeeService implements ExchangeFeeService {

    /** Seconds per hour — used for MWh energy conversion from MW power and interval duration. */
    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);

    /** Fee types processed for EPEX DA exchange spot (S8.4). */
    private static final List<String> EPEX_FEE_TYPES = List.of("TRADING", "CLEARING");

    /** Currency used for all EPEX DA fees. */
    private static final String EUR = "EUR";

    /** Central European zone for delivery day boundary computation. */
    private static final ZoneId CET = ZoneId.of("Europe/Berlin");

    private final PositionLedgerRepository ledgerRepository;
    private final ExchangeFeeScheduleRepository feeScheduleRepository;
    private final DomainEventPublisher eventPublisher;
    private final NumericPrecision numericPrecision;

    @Inject
    public DefaultExchangeFeeService(PositionLedgerRepository ledgerRepository,
                                     ExchangeFeeScheduleRepository feeScheduleRepository,
                                     DomainEventPublisher eventPublisher,
                                     NumericPrecision numericPrecision) {
        this.ledgerRepository    = Objects.requireNonNull(ledgerRepository,    "ledgerRepository");
        this.feeScheduleRepository = Objects.requireNonNull(feeScheduleRepository, "feeScheduleRepository");
        this.eventPublisher      = Objects.requireNonNull(eventPublisher,      "eventPublisher");
        this.numericPrecision    = Objects.requireNonNull(numericPrecision,    "numericPrecision");
    }

    /**
     * Compute exchange fees for all positions on a delivery day (DA-SET-03, S8.4).
     *
     * <p>Steps:
     * <ol>
     *   <li>Load all current-knowledge positions within the delivery day boundaries.</li>
     *   <li>Compute gross MWh: {@code sum( abs(quantity) * intervalHours )} across all positions.</li>
     *   <li>For each fee type (TRADING, CLEARING): resolve effective schedule and compute fee line item.</li>
     *   <li>Publish {@link ExchangeFeesComputed} via outbox.</li>
     * </ol>
     *
     * @param command the fee computation command; must not be null
     * @return the fee computation result; never null; may have empty line items if no schedules found
     */
    @Override
    public ExchangeFeeResult computeForDay(ComputeExchangeFees command) {
        Objects.requireNonNull(command, "command");

        LocalDate deliveryDay = command.deliveryDay();
        String tenantId  = command.tenantId();
        String exchange  = command.exchange();

        // Delivery day boundaries in UTC (via CET midnight → midnight)
        Instant dayStart = deliveryDay.atStartOfDay(CET).toInstant();
        Instant dayEnd   = deliveryDay.plusDays(1).atStartOfDay(CET).toInstant();

        // Load all positions for this tenant on the delivery day
        List<PositionLedgerEntry> positions = ledgerRepository.findAllByDeliveryRange(
            tenantId, dayStart, dayEnd);

        // Compute gross volume in MWh: abs(signedQty) * intervalHours per position
        BigDecimal grossVolumeMwh = computeGrossVolumeMwh(positions);

        // Compute fee line items
        List<FeeLineItem> lineItems = new ArrayList<>();
        for (String feeType : EPEX_FEE_TYPES) {
            Optional<ExchangeFeeSchedule> scheduleOpt =
                feeScheduleRepository.findEffective(tenantId, exchange, feeType, deliveryDay);
            if (scheduleOpt.isEmpty()) {
                // No configured schedule for this fee type — skip silently
                continue;
            }
            ExchangeFeeSchedule schedule = scheduleOpt.get();
            BigDecimal feeAmount = numericPrecision.round(
                grossVolumeMwh.multiply(schedule.ratePerMwh()),
                NumericPrecision.Domain.MONETARY
            );
            lineItems.add(new FeeLineItem(
                feeType,
                schedule.ratePerMwh(),
                grossVolumeMwh,
                feeAmount
            ));
        }

        // Sum total fee amount
        BigDecimal totalFeeAmount = lineItems.stream()
            .map(FeeLineItem::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        totalFeeAmount = numericPrecision.round(totalFeeAmount, NumericPrecision.Domain.MONETARY);

        // Publish event via outbox (Pattern #24)
        eventPublisher.publish(new ExchangeFeesComputed(
            tenantId, exchange, deliveryDay, totalFeeAmount, EUR, Instant.now()
        ));

        return new ExchangeFeeResult(deliveryDay, grossVolumeMwh, lineItems, totalFeeAmount, EUR);
    }

    /**
     * Compute gross MWh across all positions.
     *
     * <p>Each position carries a signed quantity in MW and a delivery interval defined by
     * {@code deliveryStart} / {@code deliveryEnd}. Gross energy = abs(qty) * intervalHours.
     * Using INTERMEDIATE scale for the intermediate product; result rounded to ENERGY scale.
     */
    private BigDecimal computeGrossVolumeMwh(List<PositionLedgerEntry> positions) {
        BigDecimal total = BigDecimal.ZERO;
        for (PositionLedgerEntry pos : positions) {
            Duration intervalDuration = Duration.between(pos.deliveryStart(), pos.deliveryEnd());
            BigDecimal intervalHours = BigDecimal.valueOf(intervalDuration.getSeconds())
                .divide(SECONDS_PER_HOUR,
                    numericPrecision.scale(NumericPrecision.Domain.INTERMEDIATE),
                    numericPrecision.roundingMode());
            BigDecimal absQty = pos.quantity().abs();
            BigDecimal mwh = numericPrecision.round(
                absQty.multiply(intervalHours),
                NumericPrecision.Domain.ENERGY
            );
            total = total.add(mwh);
        }
        return numericPrecision.round(total, NumericPrecision.Domain.ENERGY);
    }
}
