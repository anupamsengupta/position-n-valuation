package com.power.posval.domain.service.da;

import com.power.posval.domain.command.AuctionResultBatch;
import com.power.posval.domain.command.AuctionResultContract;
import com.power.posval.domain.command.ImportAuctionResults;
import com.power.posval.domain.command.TradeCapture;
import com.power.posval.domain.event.AuctionImportCompleted;
import com.power.posval.domain.event.AuctionImportFailed;
import com.power.posval.domain.model.AlertCategory;
import com.power.posval.domain.model.AlertSeverity;
import com.power.posval.domain.model.AlertStatus;
import com.power.posval.domain.model.AuctionImportSession;
import com.power.posval.domain.model.AuctionImportStatus;
import com.power.posval.domain.model.BlockDefinition;
import com.power.posval.domain.model.BlockType;
import com.power.posval.domain.model.DefaultVolumeInterval;
import com.power.posval.domain.model.DefaultVolumeSeries;
import com.power.posval.domain.model.MaterializationStatus;
import com.power.posval.domain.model.OperationalAlert;
import com.power.posval.domain.model.QualityState;
import com.power.posval.domain.model.SeriesType;
import com.power.posval.domain.model.TimeGranularity;
import com.power.posval.domain.model.VolumeUnit;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.SeriesKey;
import com.power.posval.domain.model.expression.ConstantLeaf;
import com.power.posval.domain.port.AuctionResultParser;
import com.power.posval.domain.port.MarketCalendarPort;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.repository.AuctionImportSessionRepository;
import com.power.posval.domain.port.repository.BlockDefinitionRepository;
import com.power.posval.domain.port.repository.MarketDataRepository;
import com.power.posval.domain.port.repository.PriceExpressionRepository;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;
import com.power.posval.domain.port.service.AuctionImportService;
import com.power.posval.domain.port.service.BlockDecompositionService;
import com.power.posval.domain.port.service.OperationalAlertService;
import com.power.posval.domain.service.TradeCaptureHandler;

import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Batch import orchestrator for DA auction execution reports.
 *
 * <p>Implements the 8-step pipeline defined in the tech spec §S8.1:
 * <ol>
 *   <li>Idempotency check against {@link AuctionImportSessionRepository}.</li>
 *   <li>Create {@link AuctionImportSession} with status {@code PENDING}.</li>
 *   <li>Validate the batch (interval count, MWh reconciliation, price plausibility).</li>
 *   <li>Block order decomposition via {@link BlockDecompositionService}.</li>
 *   <li>DA clearing price ingestion — market data fixing + {@code ConstantLeaf}
 *       price expression per interval (DA-PRC-01, D-2).</li>
 *   <li>Trade capture — one {@link TradeCapture} command per interval contract.</li>
 *   <li>Session completion — status {@code IMPORTED}, {@link AuctionImportCompleted}
 *       published via outbox (Pattern #24).</li>
 *   <li>Post-import operational alerts for negative and extreme prices
 *       (DA-OPS-01, DA-CLEARING-PRICES).</li>
 * </ol>
 *
 * <p>The entire pipeline runs within a single {@code UnitOfWork.execute()} scope
 * (S10.3): all ledger entries, price expressions, market data fixings, and outbox rows
 * are committed atomically. If any step fails the transaction is rolled back and the
 * session status remains {@code PENDING}.
 *
 * <p>D-2: Fixed DA prices are stored as degenerate {@link ConstantLeaf} expression trees,
 * not as raw {@code BigDecimal} values. The settlement pipeline evaluates them uniformly.
 *
 * <p>D-13 compliant: no Spring imports. Uses {@code @jakarta.inject.Inject}.
 * Pattern #18, S5.2, S8.1, DA-VOL-01, DA-PRC-01.
 */
public class DefaultAuctionImportOrchestrator implements AuctionImportService {

    /** EPEX DA price plausibility bounds (EUR/MWh). Prices outside raise alerts, not errors. */
    private static final BigDecimal PRICE_WARN_LOW    = new BigDecimal("-500");
    private static final BigDecimal PRICE_WARN_HIGH   = new BigDecimal("4000");

    /** Market data series prefix for DA clearing prices (S8.1 step 5a). */
    private static final String EPEX_DA_SERIES_PREFIX = "EPEX_DA_";

    /** Central European zone for delivery day boundaries. */
    private static final ZoneId CET = ZoneId.of("Europe/Berlin");

    /** Volume multiplier for DA spot — always 1.0 (A-6). */
    private static final BigDecimal DA_MULTIPLIER = BigDecimal.ONE;

    /** Origin type for exchange-filled trades (S8.1 step 6). */
    private static final String ORIGIN_TYPE = "EXCHANGE_FILL";

    /** Instrument type for DA exchange spot strategy dispatch (S9b.8). */
    private static final String INSTRUMENT_TYPE = "DA_EXCHANGE_SPOT";

    /** Volume series key prefix for DA PROFILE series (D-11, A-6). */
    private static final String PROFILE_PREFIX = "DA-PROFILE";

    private final AuctionImportSessionRepository sessionRepository;
    private final AuctionResultParser             resultParser;
    private final MarketCalendarPort              marketCalendarPort;
    private final BlockDecompositionService       blockDecompositionService;
    private final TradeCaptureHandler             tradeCaptureHandler;
    private final PriceExpressionRepository       priceExpressionRepository;
    private final MarketDataRepository            marketDataRepository;
    private final OperationalAlertService         alertService;
    private final DomainEventPublisher            eventPublisher;
    private final BlockDefinitionRepository       blockDefinitionRepository;
    private final VolumeSeriesRepository          volumeSeriesRepository;

    @Inject
    public DefaultAuctionImportOrchestrator(
            AuctionImportSessionRepository sessionRepository,
            AuctionResultParser             resultParser,
            MarketCalendarPort              marketCalendarPort,
            BlockDecompositionService       blockDecompositionService,
            TradeCaptureHandler             tradeCaptureHandler,
            PriceExpressionRepository       priceExpressionRepository,
            MarketDataRepository            marketDataRepository,
            OperationalAlertService         alertService,
            DomainEventPublisher            eventPublisher,
            BlockDefinitionRepository       blockDefinitionRepository,
            VolumeSeriesRepository          volumeSeriesRepository) {
        this.sessionRepository        = Objects.requireNonNull(sessionRepository,        "sessionRepository");
        this.resultParser             = Objects.requireNonNull(resultParser,             "resultParser");
        this.marketCalendarPort       = Objects.requireNonNull(marketCalendarPort,       "marketCalendarPort");
        this.blockDecompositionService = Objects.requireNonNull(blockDecompositionService, "blockDecompositionService");
        this.tradeCaptureHandler      = Objects.requireNonNull(tradeCaptureHandler,      "tradeCaptureHandler");
        this.priceExpressionRepository = Objects.requireNonNull(priceExpressionRepository, "priceExpressionRepository");
        this.marketDataRepository     = Objects.requireNonNull(marketDataRepository,     "marketDataRepository");
        this.alertService             = Objects.requireNonNull(alertService,             "alertService");
        this.eventPublisher           = Objects.requireNonNull(eventPublisher,           "eventPublisher");
        this.blockDefinitionRepository = Objects.requireNonNull(blockDefinitionRepository, "blockDefinitionRepository");
        this.volumeSeriesRepository   = Objects.requireNonNull(volumeSeriesRepository,   "volumeSeriesRepository");
    }

    /**
     * Execute the full 8-step DA auction import pipeline (S8.1, DA-VOL-01).
     *
     * <p>Idempotent: if a session with status {@code IMPORTED} already exists for
     * {@code (tenantId, exchange, biddingZone, deliveryDay)}, returns it immediately
     * without re-importing (DA-VOL-01 Scenario 5).
     *
     * @param command the import command; must not be null
     * @return the resulting (or pre-existing) {@link AuctionImportSession}; never null
     */
    @Override
    public AuctionImportSession importAuctionResults(ImportAuctionResults command) {
        Objects.requireNonNull(command, "command");

        AuctionResultBatch batch     = command.batch();
        String             tenantId  = command.tenantId();
        Instant            now       = Instant.now();

        // --- Step 1: Idempotency check (S8.1 step 1) ---
        Optional<AuctionImportSession> existing = sessionRepository.findByDeliveryDay(
            tenantId, batch.exchange(), batch.biddingZone(), batch.deliveryDay());
        if (existing.isPresent() && existing.get().status() == AuctionImportStatus.IMPORTED) {
            return existing.get();
        }

        // --- Step 2: Create session (S8.1 step 2) ---
        int expectedIntervals = marketCalendarPort.intervalCount(
            batch.biddingZone(), batch.deliveryDay(), TimeGranularity.MIN_15);

        AuctionImportSession session = AuctionImportSession.builder()
            .sessionId(UUID.randomUUID())
            .tenantId(tenantId)
            .exchange(batch.exchange())
            .biddingZone(batch.biddingZone())
            .deliveryDay(batch.deliveryDay())
            .importTimestamp(now)
            .status(AuctionImportStatus.PENDING)
            .exchangeReportedTotalMwh(batch.exchangeReportedTotalMwh())
            .intervalCount(expectedIntervals)
            .fileReference(batch.fileReference() != null ? batch.fileReference() : "")
            .createdAt(now)
            .build();
        sessionRepository.save(session);

        // --- Step 3: Validate (S8.1 step 3) ---
        session.transitionTo(AuctionImportStatus.VALIDATING);
        sessionRepository.updateStatus(tenantId, session.sessionId(),
            AuctionImportStatus.VALIDATING, null);

        List<String> validationErrors = validateBatch(batch, expectedIntervals);
        if (!validationErrors.isEmpty()) {
            for (String err : validationErrors) {
                session.addValidationError(err);
            }
            session.transitionTo(AuctionImportStatus.VALIDATION_FAILED);
            session.complete(Instant.now());
            sessionRepository.updateStatus(tenantId, session.sessionId(),
                AuctionImportStatus.VALIDATION_FAILED, session.completedAt());

            raiseIngestionAlert(tenantId, session, AlertSeverity.CRITICAL,
                "Auction result validation failed: " + String.join("; ", validationErrors));
            eventPublisher.publish(new AuctionImportFailed(
                tenantId, session.sessionId(), batch.biddingZone(), batch.deliveryDay(),
                validationErrors, Instant.now()));
            return session;
        }

        session.transitionTo(AuctionImportStatus.VALIDATED);
        sessionRepository.updateStatus(tenantId, session.sessionId(),
            AuctionImportStatus.VALIDATED, null);

        // --- Step 4: Block order decomposition (S8.1 step 4, DA-VOL-02) ---
        List<BlockDefinition> blockDefs = blockDefinitionRepository.findAllEffective(
            batch.exchange(), batch.deliveryDay());

        List<AuctionResultContract> expandedContracts = expandContracts(batch, blockDefs);

        // --- Step 5 + 6: Price ingestion and trade capture (S8.1 steps 5–6) ---
        session.transitionTo(AuctionImportStatus.IMPORTING);
        sessionRepository.updateStatus(tenantId, session.sessionId(),
            AuctionImportStatus.IMPORTING, null);

        String dataSeries = EPEX_DA_SERIES_PREFIX + batch.biddingZone();
        BigDecimal importedTotalMwh = BigDecimal.ZERO;
        List<String> priceAlertMessages = new ArrayList<>();

        for (AuctionResultContract contract : expandedContracts) {
            // Step 5a: Store DA clearing price as market data fixing (DA-PRC-01)
            MarketDataLookup fixing = new MarketDataLookup(
                contract.priceMwh(),
                0L,           // versionId 0 — will be assigned by the adapter's sequence
                dataSeries,
                contract.deliveryStart(),
                com.power.posval.domain.model.QualityState.VALIDATED
            );
            marketDataRepository.saveFixing(tenantId, dataSeries,
                contract.deliveryStart(), fixing);

            // Step 5b: Create ConstantLeaf price expression (D-2, DA-PRC-01)
            UUID priceExprId = UUID.randomUUID();
            ConstantLeaf leaf = new ConstantLeaf(
                priceExprId.toString(),
                contract.priceMwh(),
                "EUR/MWh"
            );
            priceExpressionRepository.save(priceExprId, leaf);

            // Step 6: TradeCapture command (S8.1 step 6)
            String tradeId = buildTradeId(batch, contract);
            String tradeLegId = tradeId + "/LEG1";
            SeriesKey volumeSeriesKey = SeriesKey.of(PROFILE_PREFIX, tradeId);

            DeliveryPeriod deliveryPeriod = new DeliveryPeriod(
                ZonedDateTime.ofInstant(contract.deliveryStart(), CET),
                ZonedDateTime.ofInstant(contract.deliveryEnd(), CET),
                CET
            );

            BigDecimal effectiveVolume = contract.volumeMw()
                .multiply(contract.executionRatio());

            // Step 5c: Create PROFILE volume series for this DA contract (D-11).
            // The settlement pipeline resolves volume via VolumeSeriesRepository;
            // without this series, resolveVolume() returns empty and no settlement
            // cells are generated.
            java.time.Duration intervalDur = java.time.Duration.between(
                contract.deliveryStart(), contract.deliveryEnd());
            BigDecimal intervalHrs = BigDecimal.valueOf(intervalDur.getSeconds())
                .divide(BigDecimal.valueOf(3600), 10, java.math.RoundingMode.HALF_UP);
            BigDecimal energyMwh = effectiveVolume.multiply(intervalHrs);

            DefaultVolumeInterval volumeInterval = new DefaultVolumeInterval(
                UUID.randomUUID(),
                contract.deliveryStart(),
                contract.deliveryEnd(),
                effectiveVolume,
                energyMwh,
                1,      // version
                null    // supersedesId
            );

            DefaultVolumeSeries volumeSeries = DefaultVolumeSeries.builder()
                .id(UUID.randomUUID())
                .seriesKey(volumeSeriesKey)
                .seriesType(SeriesType.PROFILE)
                .tradeLegId(tradeLegId)
                .versionId(1L)
                .volumeUnit(VolumeUnit.MW_CAPACITY)
                .granularity(TimeGranularity.MIN_15)
                .deliveryPeriod(deliveryPeriod)
                .qualityState(QualityState.VALIDATED)
                .materializationStatus(MaterializationStatus.FULL)
                .totalExpectedIntervals(1)
                .materializedIntervalCount(1)
                .transactionTime(now)
                .validTime(now)
                .intervals(List.of(volumeInterval))
                .build();
            volumeSeriesRepository.save(volumeSeries);

            TradeCapture tradeCaptureCmd = new TradeCapture(
                tradeId,
                1,                          // tradeVersion: initial capture
                tradeLegId,
                tenantId,
                deliveryPeriod,
                effectiveVolume,
                contract.direction(),
                VolumeUnit.MW_CAPACITY,
                priceExprId,
                null,                       // marketPriceExpressionId: DA self-references (DA-VAL-01)
                batch.biddingZone(),        // portfolioId: zone-level grouping
                batch.biddingZone(),        // deliveryPointId: bidding zone (S8.1 step 6)
                ORIGIN_TYPE,
                now,                        // businessEffectiveDate: auction execution time
                null,                       // assetId: null for exchange spot (S8.1)
                DA_MULTIPLIER,
                volumeSeriesKey,
                null                        // meteredSeriesKey: null for DA spot
            );
            tradeCaptureHandler.handle(tradeCaptureCmd);
            session.addTradeId(tradeId);

            // Accumulate imported MWh (abs volume * interval hours — reuse intervalHrs from step 5c)
            importedTotalMwh = importedTotalMwh.add(
                effectiveVolume.abs().multiply(intervalHrs));

            // Price plausibility alerts (S8.1 step 8) — collected here, raised after loop
            if (contract.priceMwh().compareTo(BigDecimal.ZERO) < 0) {
                priceAlertMessages.add(
                    "Negative DA clearing price " + contract.priceMwh()
                    + " EUR/MWh for interval " + contract.deliveryStart());
            } else if (contract.priceMwh().compareTo(PRICE_WARN_HIGH) > 0
                    || contract.priceMwh().compareTo(PRICE_WARN_LOW) < 0) {
                priceAlertMessages.add(
                    "Extreme DA clearing price " + contract.priceMwh()
                    + " EUR/MWh for interval " + contract.deliveryStart());
            }
        }

        // --- Step 7: Session complete (S8.1 step 7) ---
        session.setImportedTotalMwh(importedTotalMwh.setScale(8, java.math.RoundingMode.HALF_UP));
        session.transitionTo(AuctionImportStatus.IMPORTED);
        session.complete(Instant.now());
        sessionRepository.updateStatus(tenantId, session.sessionId(),
            AuctionImportStatus.IMPORTED, session.completedAt());

        eventPublisher.publish(new AuctionImportCompleted(
            tenantId, session.sessionId(), batch.biddingZone(), batch.deliveryDay(),
            session.tradeIds().size(), Instant.now()));

        // --- Step 8: Price alerts (S8.1 step 8) ---
        for (String alertMsg : priceAlertMessages) {
            boolean isNegative = alertMsg.contains("Negative");
            raiseAlert(tenantId, AlertCategory.DA_CLEARING_PRICES,
                isNegative ? AlertSeverity.INFO : AlertSeverity.WARNING,
                "DA_PRICE_PLAUSIBILITY",
                alertMsg,
                batch.deliveryDay(),
                batch.biddingZone(),
                session.sessionId().toString());
        }

        return session;
    }

    /**
     * Parse the CSV file via {@code AuctionResultParser} then delegate to
     * {@link #importAuctionResults} (S5.2, S6.3).
     *
     * @param tenantId tenant identifier (D-14, Pattern #32)
     * @param csvFile  path to the exchange feed CSV; must be readable
     * @return the resulting {@link AuctionImportSession}
     * @throws CsvParseException if the file cannot be parsed
     */
    @Override
    public AuctionImportSession importFromFile(String tenantId, Path csvFile) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(csvFile, "csvFile");
        AuctionResultBatch batch = resultParser.parse(csvFile);
        return importAuctionResults(new ImportAuctionResults(tenantId, batch));
    }

    /**
     * Retrieve a single import session by UUID (S5.2).
     *
     * @param tenantId  tenant identifier (D-14, Pattern #32)
     * @param sessionId UUID business key
     * @return the session if found
     */
    @Override
    public Optional<AuctionImportSession> getImportSession(String tenantId, UUID sessionId) {
        Objects.requireNonNull(tenantId,  "tenantId");
        Objects.requireNonNull(sessionId, "sessionId");
        return sessionRepository.findBySessionId(tenantId, sessionId);
    }

    /**
     * Retrieve import session history for a tenant, exchange, and bidding zone (S5.2).
     * Ordered by {@code importTimestamp} descending.
     *
     * @param tenantId    tenant identifier (D-14, Pattern #32)
     * @param exchange    exchange code
     * @param biddingZone bidding zone code
     * @return all sessions, most recent first; empty list if none
     */
    @Override
    public List<AuctionImportSession> getImportHistory(String tenantId, String exchange,
                                                        String biddingZone) {
        Objects.requireNonNull(tenantId,    "tenantId");
        Objects.requireNonNull(exchange,    "exchange");
        Objects.requireNonNull(biddingZone, "biddingZone");
        return sessionRepository.findByExchangeAndBiddingZone(tenantId, exchange, biddingZone);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Validate the auction batch (S8.1 step 3).
     * Returns a list of validation error messages; empty list means valid.
     *
     * <p>Checks:
     * <ul>
     *   <li>At least one contract must be present.</li>
     *   <li>Spot interval count must not <em>exceed</em> the market calendar's expected
     *       count (over-count indicates duplicates or wrong delivery day). Partial imports
     *       (fewer than the expected count) are accepted — fills may arrive incrementally
     *       as algo-traded or intraday-adjusted contracts.</li>
     *   <li>If the exchange reports a total MWh &gt; 0, the computed sum of spot contract MWh
     *       must be within 1% tolerance of that total.</li>
     * </ul>
     * Price plausibility check raises an alert but does not fail validation (S8.1 step 3c).
     */
    private List<String> validateBatch(AuctionResultBatch batch, int expectedIntervals) {
        List<String> errors = new ArrayList<>();

        // Must contain at least one contract
        if (batch.contracts().isEmpty()) {
            errors.add("Batch contains no contracts");
            return errors;
        }

        // Interval count check: spot contracts must not exceed the expected count
        // (partial imports are allowed — fills may arrive incrementally)
        long spotContractCount = batch.contracts().stream()
            .filter(c -> c.blockType() == null || c.blockType().isBlank())
            .count();
        if (spotContractCount > expectedIntervals) {
            errors.add("Spot interval count " + spotContractCount
                + " exceeds maximum " + expectedIntervals
                + " for " + batch.biddingZone() + " on " + batch.deliveryDay()
                + " — possible duplicates or wrong delivery day");
        }

        // MWh reconciliation: only when the exchange reports a non-zero total
        if (batch.exchangeReportedTotalMwh().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal computedMwh = BigDecimal.ZERO;
            for (AuctionResultContract c : batch.contracts()) {
                if (c.blockType() == null || c.blockType().isBlank()) {
                    java.time.Duration dur = java.time.Duration.between(c.deliveryStart(), c.deliveryEnd());
                    BigDecimal hours = BigDecimal.valueOf(dur.getSeconds())
                        .divide(BigDecimal.valueOf(3600), 10, java.math.RoundingMode.HALF_UP);
                    computedMwh = computedMwh.add(c.volumeMw().multiply(c.executionRatio()).multiply(hours));
                }
            }
            BigDecimal tolerance = batch.exchangeReportedTotalMwh()
                .multiply(new BigDecimal("0.01"));
            BigDecimal diff = computedMwh.subtract(batch.exchangeReportedTotalMwh()).abs();
            if (diff.compareTo(tolerance) > 0) {
                errors.add("Volume reconciliation mismatch: computed " + computedMwh
                    + " MWh vs exchange-reported " + batch.exchangeReportedTotalMwh()
                    + " MWh (diff=" + diff + ", tolerance=" + tolerance + ")");
            }
        }

        return errors;
    }

    /**
     * Expand block contracts using {@link BlockDecompositionService} (S8.1 step 4).
     * Non-block contracts are passed through unchanged as single-element additions.
     */
    private List<AuctionResultContract> expandContracts(AuctionResultBatch batch,
                                                         List<BlockDefinition> blockDefs) {
        List<AuctionResultContract> expanded = new ArrayList<>();
        for (AuctionResultContract contract : batch.contracts()) {
            if (contract.blockType() == null || contract.blockType().isBlank()) {
                expanded.add(contract);
                continue;
            }
            // Resolve block definition for this contract type
            BlockType blockType;
            try {
                blockType = BlockType.valueOf(contract.blockType().toUpperCase());
            } catch (IllegalArgumentException e) {
                // Unknown block type — log as warning, skip decomposition, pass through
                expanded.add(contract);
                continue;
            }
            Optional<BlockDefinition> defOpt = blockDefs.stream()
                .filter(d -> d.blockType() == blockType)
                .filter(d -> d.biddingZone() == null
                    || d.biddingZone().equals(batch.biddingZone()))
                .findFirst();
            List<AuctionResultContract> intervalContracts = blockDecompositionService.decompose(
                contract,
                defOpt.orElse(null),
                batch.deliveryDay()
            );
            expanded.addAll(intervalContracts);
        }
        return expanded;
    }

    /**
     * Build a stable trade ID per the convention in S8.1 step 6:
     * {@code {exchange}/{zone}/{deliveryDay}/{contractId}}.
     */
    private String buildTradeId(AuctionResultBatch batch, AuctionResultContract contract) {
        return batch.exchange() + "/"
            + batch.biddingZone() + "/"
            + batch.deliveryDay() + "/"
            + contract.contractId();
    }

    /** Raise a CRITICAL ingestion alert for session-level failures. */
    private void raiseIngestionAlert(String tenantId, AuctionImportSession session,
                                      AlertSeverity severity, String message) {
        raiseAlert(tenantId, AlertCategory.AUCTION_INGESTION, severity,
            "IMPORT_FAILURE", message,
            session.deliveryDay(), session.biddingZone(),
            session.sessionId().toString());
    }

    /** Raise an operational alert (DA-OPS-01). */
    private void raiseAlert(String tenantId,
                             AlertCategory category,
                             AlertSeverity severity,
                             String alertType,
                             String message,
                             java.time.LocalDate deliveryDay,
                             String biddingZone,
                             String sourceEventId) {
        OperationalAlert alert = OperationalAlert.builder()
            .alertId(UUID.randomUUID())
            .tenantId(tenantId)
            .category(category)
            .severity(severity)
            .alertType(alertType)
            .message(message)
            .deliveryDay(deliveryDay)
            .biddingZone(biddingZone)
            .sourceEventId(sourceEventId)
            .raisedAt(Instant.now())
            .status(AlertStatus.OPEN)
            .build();
        alertService.raise(alert);
    }
}
