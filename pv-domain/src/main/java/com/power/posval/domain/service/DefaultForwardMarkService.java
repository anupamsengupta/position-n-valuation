package com.power.posval.domain.service;

import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.expression.PriceExpression;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.port.NumericPrecision;
import com.power.posval.domain.port.cache.TradeIntervalCache;
import com.power.posval.domain.port.cache.TradeIntervalRecord;
import com.power.posval.domain.port.marketdata.MarketDataPort;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import com.power.posval.domain.port.repository.PriceExpressionRepository;
import com.power.posval.domain.port.service.ForwardMarkService;
import com.power.posval.domain.port.service.IntervalMark;
import com.power.posval.domain.port.service.MonthlyMark;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Compute-on-demand implementation of {@link ForwardMarkService} per ADR-002.
 *
 * <p>Evaluates forward MtM by combining:
 * <ul>
 *   <li>S6b resolved volumes from {@link TradeIntervalCache}</li>
 *   <li>S4 forward curve prices via {@link PriceEvaluator} with
 *       {@link ResolutionPurpose#FORWARD}</li>
 * </ul>
 *
 * <p>For each interval: {@code markValue = evaluatedPrice × resolvedEnergy}.
 * Monthly marks aggregate across all intervals in the range.
 *
 * <p>D-3: Forward marks are ephemeral (computed, not stored).
 * D-13: No Spring types. Pattern #18 (Port + Adapter), ADR-002.
 */
public class DefaultForwardMarkService implements ForwardMarkService {

    private static final Logger log = LoggerFactory.getLogger(DefaultForwardMarkService.class);

    private static final ZoneId DEFAULT_TIMEZONE = ZoneId.of("Europe/Berlin");

    private final PositionLedgerRepository ledgerRepo;
    private final TradeIntervalCache tradeIntervalCache;
    private final PriceExpressionRepository priceExpressionRepo;
    private final PriceEvaluator priceEvaluator;
    private final MarketDataPort marketData;
    private final NumericPrecision np;

    @Inject
    public DefaultForwardMarkService(PositionLedgerRepository ledgerRepo,
                                      TradeIntervalCache tradeIntervalCache,
                                      PriceExpressionRepository priceExpressionRepo,
                                      PriceEvaluator priceEvaluator,
                                      MarketDataPort marketData,
                                      NumericPrecision np) {
        this.ledgerRepo = ledgerRepo;
        this.tradeIntervalCache = tradeIntervalCache;
        this.priceExpressionRepo = priceExpressionRepo;
        this.priceEvaluator = priceEvaluator;
        this.marketData = marketData;
        this.np = np;
    }

    /**
     * Compute MtM for a position over [monthStart, monthEnd).
     *
     * <p>ADR-002 flow:
     * <ol>
     *   <li>Load position from S1 → get price expression ref, trade-leg ID</li>
     *   <li>Load S6b intervals for trade-leg + range → resolved volumes</li>
     *   <li>Evaluate price expression against S4 curves (Purpose=FORWARD)</li>
     *   <li>For each interval: markValue = evaluatedPrice × resolvedEnergy</li>
     *   <li>Aggregate: forwardMtm = sum(markValue), totalMwh = sum(resolvedEnergy)</li>
     * </ol>
     */
    @Override
    public MonthlyMark computeMonthlyMark(String tenantId, UUID positionId,
                                           Instant monthStart, Instant monthEnd) {
        var posOpt = ledgerRepo.findById(positionId);
        if (posOpt.isEmpty()) {
            log.warn("computeMonthlyMark: position {} not found", positionId);
            return zeroMonthlyMark(positionId, monthStart, monthEnd);
        }

        PositionLedgerEntry pos = posOpt.get();
        ZoneId tz = resolveTimezone(pos);

        // Load S6b intervals
        List<TradeIntervalRecord> intervals = tradeIntervalCache.getForTradeLeg(
            tenantId, pos.tradeLegId(), monthStart, monthEnd);

        if (intervals.isEmpty()) {
            log.debug("computeMonthlyMark: no S6b intervals for position {} in [{}, {})",
                positionId, monthStart, monthEnd);
            return zeroMonthlyMark(positionId, monthStart, monthEnd);
        }

        // Load price expression
        UUID priceExprId = pos.priceExpressionId();
        PriceExpression expression = loadExpression(priceExprId);
        if (expression == null) {
            log.warn("computeMonthlyMark: price expression {} not found for position {}",
                priceExprId, positionId);
            return zeroMonthlyMark(positionId, monthStart, monthEnd);
        }

        // Evaluate and aggregate
        BigDecimal totalMtm = BigDecimal.ZERO;
        BigDecimal totalMwh = BigDecimal.ZERO;
        String curveId = null;
        long curveVersion = 0L;
        long volumeVersion = 0L;

        for (TradeIntervalRecord interval : intervals) {
            DeliveryPeriod deliveryPeriod = new DeliveryPeriod(
                ZonedDateTime.ofInstant(interval.intervalStart(), tz),
                ZonedDateTime.ofInstant(interval.intervalEnd(), tz),
                tz);

            PriceResolution priceRes = priceEvaluator.evaluate(
                expression, deliveryPeriod, ResolutionPurpose.FORWARD, marketData);

            BigDecimal energy = interval.resolvedEnergy() != null
                ? interval.resolvedEnergy() : BigDecimal.ZERO;
            BigDecimal markValue = np.round(
                priceRes.value().multiply(energy),
                NumericPrecision.Domain.MONETARY);

            totalMtm = totalMtm.add(markValue);
            totalMwh = totalMwh.add(energy);

            // Track curve metadata from the first non-empty resolution
            if (curveId == null && !priceRes.inputVersionSet().isEmpty()) {
                Map.Entry<String, Long> first =
                    priceRes.inputVersionSet().entrySet().iterator().next();
                curveId = first.getKey();
                curveVersion = first.getValue();
            }

            // Track volume version from S6b
            if (interval.versionHash() != null && volumeVersion == 0L) {
                volumeVersion = interval.versionHash().hashCode();
            }
        }

        BigDecimal forwardMtm = np.round(totalMtm, NumericPrecision.Domain.MONETARY);
        BigDecimal roundedMwh = np.round(totalMwh, NumericPrecision.Domain.ENERGY);
        BigDecimal avgPrice = roundedMwh.signum() != 0
            ? np.round(forwardMtm.divide(roundedMwh,
                np.scale(NumericPrecision.Domain.PRICE), np.roundingMode()),
                NumericPrecision.Domain.PRICE)
            : BigDecimal.ZERO;

        return new MonthlyMark(
            positionId, monthStart, monthEnd,
            forwardMtm, roundedMwh, avgPrice,
            curveId, curveVersion, volumeVersion,
            "EUR");
    }

    /**
     * Compute MtM per interval for a position-day. Used by L4 forward view.
     *
     * <p>ADR-002 flow:
     * <ol>
     *   <li>Load position from S1</li>
     *   <li>Load S6b intervals for trade-leg + day range</li>
     *   <li>Evaluate price expression per interval (Purpose=FORWARD)</li>
     *   <li>Return List&lt;IntervalMark&gt; with per-interval detail</li>
     * </ol>
     */
    @Override
    public List<IntervalMark> computeIntervalMarks(String tenantId, UUID positionId,
                                                    Instant dayStart, Instant dayEnd) {
        var posOpt = ledgerRepo.findById(positionId);
        if (posOpt.isEmpty()) {
            log.warn("computeIntervalMarks: position {} not found", positionId);
            return List.of();
        }

        PositionLedgerEntry pos = posOpt.get();
        ZoneId tz = resolveTimezone(pos);

        // Load S6b intervals
        List<TradeIntervalRecord> intervals = tradeIntervalCache.getForTradeLeg(
            tenantId, pos.tradeLegId(), dayStart, dayEnd);

        if (intervals.isEmpty()) {
            log.debug("computeIntervalMarks: no S6b intervals for position {} in [{}, {})",
                positionId, dayStart, dayEnd);
            return List.of();
        }

        // Load price expression
        PriceExpression expression = loadExpression(pos.priceExpressionId());
        if (expression == null) {
            log.warn("computeIntervalMarks: price expression not found for position {}",
                positionId);
            return List.of();
        }

        List<IntervalMark> marks = new ArrayList<>(intervals.size());
        for (TradeIntervalRecord interval : intervals) {
            DeliveryPeriod deliveryPeriod = new DeliveryPeriod(
                ZonedDateTime.ofInstant(interval.intervalStart(), tz),
                ZonedDateTime.ofInstant(interval.intervalEnd(), tz),
                tz);

            PriceResolution priceRes = priceEvaluator.evaluate(
                expression, deliveryPeriod, ResolutionPurpose.FORWARD, marketData);

            BigDecimal energy = interval.resolvedEnergy() != null
                ? interval.resolvedEnergy() : BigDecimal.ZERO;
            BigDecimal qty = interval.resolvedQty() != null
                ? interval.resolvedQty() : BigDecimal.ZERO;
            BigDecimal markValue = np.round(
                priceRes.value().multiply(energy),
                NumericPrecision.Domain.MONETARY);

            // Extract curve metadata
            String curveId = null;
            long curveVersion = 0L;
            if (!priceRes.inputVersionSet().isEmpty()) {
                Map.Entry<String, Long> first =
                    priceRes.inputVersionSet().entrySet().iterator().next();
                curveId = first.getKey();
                curveVersion = first.getValue();
            }

            marks.add(new IntervalMark(
                interval.intervalStart(),
                interval.intervalEnd(),
                positionId,
                pos.tradeLegId(),
                qty,
                energy,
                priceRes.value(),
                markValue,
                curveId,
                curveVersion,
                "EUR"));
        }

        return marks;
    }

    /**
     * Compute portfolio-level MtM for a period by summing across all
     * active forward positions in the portfolio.
     *
     * <p>Used by the rollup pipeline to populate {@code forwardMarkValue}
     * on rollup cells. ADR-002 §S7.
     */
    @Override
    public BigDecimal computePortfolioMtm(String tenantId, String portfolioId,
                                           Instant periodStart, Instant periodEnd) {
        List<PositionLedgerEntry> positions = ledgerRepo.findByPortfolioAndDeliveryRange(
            tenantId, portfolioId, periodStart, periodEnd);

        Instant now = Instant.now();
        // Only forward positions (delivery extends beyond now)
        List<PositionLedgerEntry> forwardPositions = positions.stream()
            .filter(p -> p.deliveryEnd() != null && p.deliveryEnd().isAfter(now))
            .toList();

        if (forwardPositions.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal totalMtm = BigDecimal.ZERO;
        Instant forwardStart = now.isAfter(periodStart) ? now : periodStart;

        for (PositionLedgerEntry pos : forwardPositions) {
            try {
                MonthlyMark mark = computeMonthlyMark(
                    tenantId, pos.id(), forwardStart, periodEnd);
                totalMtm = totalMtm.add(mark.forwardMtm());
            } catch (Exception ex) {
                log.warn("computePortfolioMtm: failed for position {} in portfolio {}: {}",
                    pos.id(), portfolioId, ex.getMessage());
            }
        }

        return np.round(totalMtm, NumericPrecision.Domain.MONETARY);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private PriceExpression loadExpression(UUID expressionId) {
        if (expressionId == null) return null;
        return priceExpressionRepo.findById(expressionId).orElse(null);
    }

    private ZoneId resolveTimezone(PositionLedgerEntry pos) {
        if (pos.deliveryRange() != null && pos.deliveryRange().deliveryTimezone() != null) {
            return pos.deliveryRange().deliveryTimezone();
        }
        return DEFAULT_TIMEZONE;
    }

    private MonthlyMark zeroMonthlyMark(UUID positionId, Instant monthStart, Instant monthEnd) {
        return new MonthlyMark(
            positionId, monthStart, monthEnd,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            null, 0L, 0L, "EUR");
    }
}
