package com.power.posval.domain.port;

import com.power.posval.domain.command.AuctionResultBatch;

import java.nio.file.Path;

/**
 * Port interface for parsing exchange feed files into the {@link AuctionResultBatch}
 * domain object.
 *
 * <p>The parser is format-specific. v1 supports EPEX DA CSV only. Future formats
 * (XML, REST API response, etc.) implement this same port so the import orchestrator
 * remains format-agnostic.
 *
 * <p>The {@code pv-persistence} module provides the concrete adapter
 * {@code EpexCsvAuctionResultParser} for the EPEX DA execution report CSV format (S6.2).
 *
 * <p>Pattern #18 (Port Interface), S5.1.
 */
public interface AuctionResultParser {

    /**
     * Parse the given file into an {@link AuctionResultBatch}.
     *
     * <p>Structural validation is performed during parse:
     * <ul>
     *   <li>All required columns must be present.</li>
     *   <li>All rows must be parseable (numeric fields, ISO timestamps, valid direction).</li>
     *   <li>No duplicate {@code contractId} values within one file.</li>
     * </ul>
     *
     * <p>Business validation (interval count, volume reconciliation) is the responsibility
     * of the import orchestrator and is NOT performed here.
     *
     * @param csvFile the path to the feed file; must be readable and non-null
     * @return the parsed batch; never null
     * @throws com.power.posval.domain.exception.CsvParseException if structural parsing fails;
     *         the exception carries the line number and error detail
     */
    AuctionResultBatch parse(Path csvFile);

    /**
     * Return the format identifier this parser supports, e.g. {@code "EPEX_DA_CSV_V1"}.
     * Used for logging, audit, and future multi-parser routing.
     *
     * @return a non-null, non-blank format string
     */
    String supportedFormat();
}
