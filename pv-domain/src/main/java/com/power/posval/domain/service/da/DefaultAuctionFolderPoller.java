package com.power.posval.domain.service.da;

import com.power.posval.domain.model.AuctionImportSession;
import com.power.posval.domain.model.AuctionImportStatus;
import com.power.posval.domain.model.AlertCategory;
import com.power.posval.domain.model.AlertSeverity;
import com.power.posval.domain.model.AlertStatus;
import com.power.posval.domain.model.OperationalAlert;
import com.power.posval.domain.model.value.AuctionFolderPollerConfig;
import com.power.posval.domain.port.AuctionResultParser;
import com.power.posval.domain.port.service.AuctionFolderPoller;
import com.power.posval.domain.port.service.AuctionImportService;
import com.power.posval.domain.port.service.OperationalAlertService;
import com.power.posval.domain.command.AuctionResultBatch;
import com.power.posval.domain.command.ImportAuctionResults;

import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Library-scope implementation of {@link AuctionFolderPoller}.
 *
 * <p>Scans the configured {@code inboxDirectory} for new EPEX CSV files on each
 * {@link #pollOnce()} call, delegates to {@link AuctionResultParser} and
 * {@link AuctionImportService}, then moves each file to either the
 * {@code processedDirectory} (on success) or the {@code failedDirectory}
 * (on parse or validation failure) (S6.3).
 *
 * <p>Scheduling is the host's responsibility:
 * <ul>
 *   <li>In {@code pv-app} (simulator): a Spring {@code @Scheduled} method calls
 *       {@link #pollOnce()} on a fixed-delay basis (D-14 — scheduling annotation is
 *       simulator-scope only, never here).</li>
 *   <li>In the production host (future): a cron job or ECS scheduled task calls
 *       {@link #pollOnce()}.</li>
 * </ul>
 *
 * <p>Uses only {@code java.nio.file} APIs — zero framework dependency (D-13).
 *
 * <p>File naming convention: {@code {exchange}_{zone}_{YYYYMMDD}.csv}
 * (e.g. {@code EPEX_SPOT_DE_LU_20260916.csv}).
 * The tenant context is read from {@link AuctionFolderPollerConfig#defaultTenantId}.
 *
 * <p>D-13 compliant: no Spring imports. Uses {@code @jakarta.inject.Inject}.
 * Pattern #18, S5.2, S6.3, DA-VOL-01.
 */
public class DefaultAuctionFolderPoller implements AuctionFolderPoller {

    private static final Logger LOG = Logger.getLogger(DefaultAuctionFolderPoller.class.getName());

    /** Central European zone used to name the processed/failed subdirectories. */
    private static final ZoneId CET = ZoneId.of("Europe/Berlin");

    private final AuctionResultParser       resultParser;
    private final AuctionImportService      importService;
    private final OperationalAlertService   alertService;
    private final AuctionFolderPollerConfig config;

    /** Lifecycle flag: true while the poller is active. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Inject
    public DefaultAuctionFolderPoller(AuctionResultParser       resultParser,
                                       AuctionImportService      importService,
                                       OperationalAlertService   alertService,
                                       AuctionFolderPollerConfig config) {
        this.resultParser  = Objects.requireNonNull(resultParser,  "resultParser");
        this.importService = Objects.requireNonNull(importService,  "importService");
        this.alertService  = Objects.requireNonNull(alertService,   "alertService");
        this.config        = Objects.requireNonNull(config,         "config");
    }

    /**
     * Perform one polling cycle: scan, sort, process, move (S6.3).
     *
     * <p>Files are processed in alphabetical filename order for deterministic processing.
     * Already-imported sessions (idempotency) result in the file being moved to
     * {@code processed/} without re-triggering an import.
     *
     * @return list of {@link AuctionImportSession} results from this cycle;
     *         empty if no files were found
     */
    @Override
    public List<AuctionImportSession> pollOnce() {
        List<AuctionImportSession> results = new ArrayList<>();

        if (!Files.isDirectory(config.inboxDirectory())) {
            LOG.warning("Inbox directory does not exist or is not a directory: "
                + config.inboxDirectory());
            return results;
        }

        PathMatcher matcher = FileSystems.getDefault()
            .getPathMatcher("glob:" + config.filePattern());

        List<Path> files;
        try (var stream = Files.list(config.inboxDirectory())) {
            files = stream
                .filter(p -> Files.isRegularFile(p) && matcher.matches(p.getFileName()))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to list inbox directory: " + config.inboxDirectory(), e);
            return results;
        }

        for (Path file : files) {
            AuctionImportSession session = processFile(file);
            if (session != null) {
                results.add(session);
            }
        }

        return results;
    }

    /**
     * Activate the poller. In {@code pv-app} (simulator) the Spring {@code @Scheduled}
     * wrapper calls {@link #pollOnce()} directly, so {@code start()} only sets the flag.
     * A dedicated background thread may be started here when running outside Spring.
     */
    @Override
    public void start() {
        running.set(true);
        LOG.info("AuctionFolderPoller started. Inbox: " + config.inboxDirectory());
    }

    /**
     * Deactivate the poller. Future {@link #pollOnce()} calls are no-ops while stopped.
     */
    @Override
    public void stop() {
        running.set(false);
        LOG.info("AuctionFolderPoller stopped.");
    }

    /**
     * Return {@code true} if the poller is currently active.
     *
     * @return {@code true} if {@link #start()} has been called and {@link #stop()} has not
     */
    @Override
    public boolean isRunning() {
        return running.get();
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Process a single file: parse, import, move to processed or failed.
     * Returns the resulting session, or {@code null} if the file could not be processed
     * at all (e.g. IO error).
     */
    private AuctionImportSession processFile(Path file) {
        String filename = file.getFileName().toString();
        LOG.info("Processing auction file: " + filename);

        try {
            AuctionResultBatch batch = resultParser.parse(file);
            AuctionImportSession session = importService.importAuctionResults(
                new ImportAuctionResults(config.defaultTenantId(), batch));

            // Move to processed regardless of whether this was a re-import (idempotent)
            moveToProcessed(file, batch.deliveryDay());
            return session;

        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to process auction file: " + filename, e);
            moveToFailed(file);

            raiseIngestionAlert(
                config.defaultTenantId(),
                "CSV parse or import failed for file '" + filename + "': " + e.getMessage(),
                file
            );
            return null;
        }
    }

    /**
     * Move a successfully processed file to {@code processedDirectory/{YYYY-MM-DD}/}.
     * The subdirectory is named after today's date in CET (S6.3 directory structure).
     */
    private void moveToProcessed(Path file, LocalDate deliveryDay) {
        try {
            Path targetDir = config.processedDirectory()
                .resolve(deliveryDay.toString());  // YYYY-MM-DD subdirectory
            Files.createDirectories(targetDir);
            Files.move(file, targetDir.resolve(file.getFileName()),
                StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not move processed file to processed dir: " + file, e);
        }
    }

    /**
     * Move a failed file to {@code failedDirectory/{today's-date}/}.
     * Uses today's CET date for the subdirectory name.
     */
    private void moveToFailed(Path file) {
        try {
            String today = LocalDate.now(CET).toString();  // YYYY-MM-DD
            Path targetDir = config.failedDirectory().resolve(today);
            Files.createDirectories(targetDir);
            Files.move(file, targetDir.resolve(file.getFileName()),
                StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not move failed file to failed dir: " + file, e);
        }
    }

    /**
     * Raise a CRITICAL {@code AUCTION_INGESTION} alert for a file processing failure
     * (S6.3, DA-OPS-01).
     */
    private void raiseIngestionAlert(String tenantId, String message, Path file) {
        try {
            OperationalAlert alert = OperationalAlert.builder()
                .alertId(UUID.randomUUID())
                .tenantId(tenantId)
                .category(AlertCategory.AUCTION_INGESTION)
                .severity(AlertSeverity.CRITICAL)
                .alertType("CSV_IMPORT_FAILURE")
                .message(message)
                .deliveryDay(null)      // delivery day not known if parse failed
                .biddingZone(null)
                .sourceEventId(file.getFileName().toString())
                .raisedAt(Instant.now())
                .status(AlertStatus.OPEN)
                .build();
            alertService.raise(alert);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Failed to raise ingestion alert for file: " + file, ex);
        }
    }
}
