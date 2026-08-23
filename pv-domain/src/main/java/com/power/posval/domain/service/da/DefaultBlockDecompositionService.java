package com.power.posval.domain.service.da;

import com.power.posval.domain.command.AuctionResultContract;
import com.power.posval.domain.model.BlockDefinition;
import com.power.posval.domain.model.TimeGranularity;
import com.power.posval.domain.port.MarketCalendarPort;
import com.power.posval.domain.port.repository.BlockDefinitionRepository;
import com.power.posval.domain.port.repository.HolidayCalendarRepository;
import com.power.posval.domain.port.service.BlockDecompositionService;

import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Default implementation of {@link BlockDecompositionService}.
 *
 * <p>Expands a block-order {@link AuctionResultContract} into constituent 15-min delivery
 * intervals using {@link MarketCalendarPort#intervalsForDay} for DST-correct interval
 * generation. The block definition's CET time window ({@code startHour} / {@code endHour})
 * is used to filter which intervals belong to the block product (DA-VOL-02).
 *
 * <p>The {@code executionRatio} is applied uniformly to all expanded intervals:
 * {@code expandedVolumeMw = contract.volumeMw() * contract.executionRatio()}.
 * Volume is not rounded here — the caller (import orchestrator) rounds at persistence time
 * using {@link com.power.posval.domain.port.NumericPrecision#PRICE}.
 *
 * <p>For individual (non-block) contracts ({@code blockType == null} or blank),
 * the contract is returned as-is in a single-element list.
 *
 * <p>D-13 compliant: no Spring imports. Uses {@code @jakarta.inject.Inject}.
 * Pattern #18, DA-VOL-02.
 */
public class DefaultBlockDecompositionService implements BlockDecompositionService {

    /** Central European zone used for all EPEX block definitions (CET/CEST). */
    private static final ZoneId CET = ZoneId.of("Europe/Berlin");

    private final MarketCalendarPort marketCalendarPort;
    private final HolidayCalendarRepository holidayCalendarRepository;
    private final BlockDefinitionRepository blockDefinitionRepository;

    @Inject
    public DefaultBlockDecompositionService(MarketCalendarPort marketCalendarPort,
                                             HolidayCalendarRepository holidayCalendarRepository,
                                             BlockDefinitionRepository blockDefinitionRepository) {
        this.marketCalendarPort       = Objects.requireNonNull(marketCalendarPort, "marketCalendarPort");
        this.holidayCalendarRepository = Objects.requireNonNull(holidayCalendarRepository, "holidayCalendarRepository");
        this.blockDefinitionRepository = Objects.requireNonNull(blockDefinitionRepository, "blockDefinitionRepository");
    }

    /**
     * Decompose a block-order contract into its constituent 15-min interval contracts.
     *
     * <p>Algorithm (DA-VOL-02):
     * <ol>
     *   <li>If {@code blockType} is null/blank, return the contract as-is.</li>
     *   <li>Obtain all QUARTER_HOURLY intervals for the delivery day from
     *       {@link MarketCalendarPort#intervalsForDay}.</li>
     *   <li>Filter intervals that fall within the block definition's CET time window
     *       ({@code startHour} .. {@code endHour} exclusive).</li>
     *   <li>For PEAK blocks: additionally check
     *       {@link MarketCalendarPort#isPeakInterval} — if the day is a holiday per the
     *       block definition's {@code holidayCalendarRef}, return an empty list
     *       (EPEX convention: PEAK products don't execute on public holidays).</li>
     *   <li>Apply {@code executionRatio} to each expanded interval's volume.</li>
     *   <li>Produce a new {@link AuctionResultContract} per interval, inheriting
     *       {@code priceMwh}, {@code direction}, and the computed volume.</li>
     * </ol>
     *
     * @param contract    the block-order contract to decompose; never null
     * @param blockDef    the effective block definition; never null if contract has a blockType
     * @param deliveryDay the CET-interpreted delivery date
     * @return expanded interval contracts; single-element list for non-block contracts
     */
    @Override
    public List<AuctionResultContract> decompose(AuctionResultContract contract,
                                                  BlockDefinition blockDef,
                                                  LocalDate deliveryDay) {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(deliveryDay, "deliveryDay");

        // Individual (non-block) contract — pass through as-is
        if (contract.blockType() == null || contract.blockType().isBlank()) {
            return List.of(contract);
        }

        Objects.requireNonNull(blockDef, "blockDef must not be null for block contracts");

        // Check if the delivery day is a holiday for PEAK blocks
        if ("PEAK".equalsIgnoreCase(contract.blockType())
                && blockDef.holidayCalendarRef() != null) {
            if (holidayCalendarRepository.isHoliday(blockDef.holidayCalendarRef(), deliveryDay)) {
                // EPEX convention: PEAK product does not execute on public holidays
                return List.of();
            }
        }

        // Determine bidding zone from block definition; fall back to extracting from contractId
        String biddingZone = blockDef.biddingZone() != null
            ? blockDef.biddingZone()
            : deriveBiddingZoneFromContract(contract);

        // Get all quarter-hourly intervals for the delivery day (DST-correct)
        var allIntervals = marketCalendarPort.intervalsForDay(
            biddingZone, deliveryDay, TimeGranularity.MIN_15);

        // Effective volume after execution ratio is applied
        BigDecimal effectiveVolumeMw = contract.volumeMw().multiply(contract.executionRatio());

        // Block definition time window in CET
        ZonedDateTime blockStartCet = deliveryDay.atTime(blockDef.startHour()).atZone(CET);
        ZonedDateTime blockEndCet   = deliveryDay.atTime(blockDef.endHour()).atZone(CET);

        List<AuctionResultContract> expanded = new ArrayList<>();
        int intervalIndex = 0;
        for (var interval : allIntervals) {
            intervalIndex++;
            // Include only intervals whose start falls within [blockStart, blockEnd)
            ZonedDateTime intervalStart = interval.start();
            if (!intervalStart.isBefore(blockStartCet) && intervalStart.isBefore(blockEndCet)) {
                Instant iStart = intervalStart.toInstant();
                Instant iEnd   = interval.end().toInstant();
                String expandedContractId = contract.contractId()
                    + "/interval-" + intervalIndex;
                expanded.add(new AuctionResultContract(
                    expandedContractId,
                    contract.priceMwh(),
                    effectiveVolumeMw,
                    iStart,
                    iEnd,
                    null,                       // expanded intervals are no longer block orders
                    BigDecimal.ONE,             // executionRatio already applied above
                    contract.direction()
                ));
            }
        }
        return List.copyOf(expanded);
    }

    /**
     * Attempt to derive a bidding zone from the contract ID when the block definition
     * does not specify one. The EPEX contract ID convention is
     * {@code {ZONE}-{YYYYMMDD}-{HHMM}} (e.g. {@code DELU-20260916-0000}).
     * Falls back to {@code "DE_LU"} as a safe default for EPEX continental contracts.
     */
    private String deriveBiddingZoneFromContract(AuctionResultContract contract) {
        // Attempt to extract from contractId prefix: "DELU-...", "FR-...", etc.
        // This is a best-effort fallback; production deployments should always
        // have the block definition include the bidding zone.
        String id = contract.contractId();
        if (id.startsWith("FR")) {
            return "FR";
        }
        if (id.startsWith("AT")) {
            return "AT";
        }
        // Default to EPEX DE_LU for all other zones
        return "DE_LU";
    }
}
