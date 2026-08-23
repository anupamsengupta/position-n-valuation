package com.power.posval.domain.port.service;

import com.power.posval.domain.command.ImportAuctionResults;
import com.power.posval.domain.model.AuctionImportSession;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service port for DA auction result batch imports.
 *
 * <p>{@link #importAuctionResults} is the primary entry point for the import orchestrator.
 * The entire import (validation, block decomposition, price ingestion, trade capture,
 * session update, event publishing) runs within a single {@code UnitOfWork.execute()}
 * and is therefore atomic (S10.3).
 *
 * <p>Idempotency: if a session with status {@code IMPORTED} already exists for the same
 * {@code (tenantId, exchange, biddingZone, deliveryDay)} tuple, the import is a no-op
 * and the existing session is returned (DA-VOL-01 Scenario 5).
 *
 * <p>Pattern #18 (Service Port), S5.2.
 */
public interface AuctionImportService {

    /**
     * Import auction results from a pre-parsed {@link ImportAuctionResults} command.
     *
     * <p>Steps: idempotency check → session creation → validation → block decomposition
     * → DA price ingestion → trade capture (per interval) → session completion
     * → event publishing (S8.1).
     *
     * @param command the import command carrying the parsed batch; must not be null
     * @return the resulting (or pre-existing) {@link AuctionImportSession}; never null
     */
    AuctionImportSession importAuctionResults(ImportAuctionResults command);

    /**
     * Convenience entry point that parses the CSV file via {@code AuctionResultParser}
     * and then delegates to {@link #importAuctionResults}.
     *
     * <p>Used by the folder poller and the simulator REST endpoint
     * {@code POST /api/da/import/file} (S9.4).
     *
     * @param tenantId tenant identifier (D-14, Pattern #32)
     * @param csvFile  path to the exchange feed CSV file; must be readable
     * @return the resulting {@link AuctionImportSession}; never null
     * @throws com.power.posval.domain.exception.CsvParseException if the file cannot be parsed
     */
    AuctionImportSession importFromFile(String tenantId, Path csvFile);

    /**
     * Retrieve a single import session by its UUID business key.
     *
     * @param tenantId  tenant identifier (D-14, Pattern #32)
     * @param sessionId UUID business key of the session
     * @return the session if it exists for this tenant
     */
    Optional<AuctionImportSession> getImportSession(String tenantId, UUID sessionId);

    /**
     * Retrieve the import session history for a tenant, exchange, and bidding zone,
     * ordered by {@code importTimestamp} descending (most recent first).
     *
     * @param tenantId    tenant identifier (D-14, Pattern #32)
     * @param exchange    exchange code, e.g. {@code "EPEX_SPOT"}
     * @param biddingZone bidding zone code, e.g. {@code "DE_LU"}
     * @return import session history; empty list if none found
     */
    List<AuctionImportSession> getImportHistory(String tenantId, String exchange,
                                                 String biddingZone);
}
