package com.power.posval.domain.port.service;

import com.power.posval.domain.model.AuctionImportSession;

import java.util.List;

/**
 * Service port for the file-system auction result folder poller.
 *
 * <p>The poller watches a configured inbox directory for new exchange feed CSV files,
 * parses each via {@code AuctionResultParser}, and delegates to {@code AuctionImportService}.
 * Successfully processed files are moved to the {@code processed/} directory;
 * failed files are moved to the {@code failed/} directory and an
 * {@code OperationalAlert(CRITICAL, AUCTION_INGESTION)} is raised (S6.3).
 *
 * <p>Scheduling is the host's responsibility:
 * <ul>
 *   <li>In {@code pv-app} (simulator): a Spring {@code @Scheduled} method calls
 *       {@link #pollOnce()} on a fixed-delay basis. This is simulator-scope only (D-14).</li>
 *   <li>In the production host (future): a cron job or ECS scheduled task calls
 *       {@link #pollOnce()} externally.</li>
 * </ul>
 *
 * <p>The implementation ({@code DefaultAuctionFolderPoller} in {@code pv-domain/service/})
 * uses only {@code java.nio.file.Files} — no framework dependency (S6.3, D-13).
 *
 * <p>Pattern #18 (Service Port), S5.2.
 */
public interface AuctionFolderPoller {

    /**
     * Perform one polling cycle: scan the inbox directory, process all eligible files,
     * and return the list of {@link AuctionImportSession} results.
     *
     * <p>Files are processed in deterministic order (sorted by filename). Already-imported
     * files are moved to {@code processed/} without re-triggering an import (idempotent).
     *
     * @return the list of sessions produced or identified during this poll cycle;
     *         empty list if no files were found
     */
    List<AuctionImportSession> pollOnce();

    /**
     * Start background polling if the implementation supports it.
     * In {@code pv-app} (simulator) polling is driven by {@code @Scheduled}; this method
     * may be a no-op or may start an internal daemon thread depending on the host.
     */
    void start();

    /**
     * Stop background polling. In {@code pv-app} (simulator) this is a no-op because the
     * Spring scheduler owns the lifecycle.
     */
    void stop();

    /**
     * Return {@code true} if the poller is currently active and will process files on the
     * next {@link #pollOnce()} call.
     *
     * @return {@code true} if the poller is running
     */
    boolean isRunning();
}
