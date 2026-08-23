package com.power.posval.persistence.adapter;

import com.power.posval.domain.command.AuctionResultBatch;
import com.power.posval.domain.command.AuctionResultContract;
import com.power.posval.domain.exception.CsvParseException;
import com.power.posval.domain.model.TradeDirection;
import com.power.posval.domain.port.AuctionResultParser;
import jakarta.inject.Inject;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * EPEX DA auction result CSV parser adapter.
 *
 * <p>Implements {@link AuctionResultParser} for the EPEX DA execution report CSV format
 * as defined in S6.2. Performs structural validation only — business validation
 * (interval count, volume reconciliation, etc.) is the orchestrator's responsibility.
 *
 * <p><b>Expected CSV format (S6.2):</b>
 * <pre>
 * # EPEX_SPOT DA Auction Results
 * # Delivery Day: 2026-09-16
 * # Bidding Zone: DE_LU
 * # Total MWh: 12450.00
 * ContractId,DeliveryStart,DeliveryEnd,Price,Volume,Direction,BlockType,ExecutionRatio
 * DELU-20260916-0000,2026-09-15T22:00:00Z,2026-09-15T22:15:00Z,45.20,30.0,BUY,,1.0
 * ...
 * </pre>
 *
 * <p>Parsing rules (S6.2):
 * <ol>
 *   <li>Lines starting with {@code #} are header metadata. Extracts {@code Delivery Day},
 *       {@code Bidding Zone}, and {@code Total MWh}.</li>
 *   <li>First non-comment, non-empty line is the column header row.</li>
 *   <li>All required columns must be present in the header row.</li>
 *   <li>{@code DeliveryStart}/{@code DeliveryEnd} are ISO-8601 UTC instants.</li>
 *   <li>{@code Price} is EUR/MWh, decimal (may be negative).</li>
 *   <li>{@code Volume} is MW, positive decimal.</li>
 *   <li>{@code Direction} is {@code BUY} or {@code SELL}.</li>
 *   <li>{@code BlockType} is empty for individual spot contracts; {@code BASELOAD},
 *       {@code PEAK}, {@code OFF_PEAK}, or {@code CUSTOM} for block orders.</li>
 *   <li>{@code ExecutionRatio} is {@code 1.0} for fully executed; in {@code [0.0, 1.0]}
 *       for partial blocks.</li>
 *   <li>Duplicate {@code ContractId} values within one file raise a parse error.</li>
 *   <li>Encoding: UTF-8. Line separator: {@code \n} or {@code \r\n}. Decimal separator: {@code .}</li>
 * </ol>
 *
 * <p>Pattern #18 (Port + Adapter), S6.2, DA-VOL-01.
 */
public class EpexCsvAuctionResultParser implements AuctionResultParser {

    private static final String SUPPORTED_FORMAT = "EPEX_DA_CSV_V1";

    // Required column names in the header row
    private static final List<String> REQUIRED_COLUMNS = List.of(
        "ContractId", "DeliveryStart", "DeliveryEnd", "Price",
        "Volume", "Direction", "BlockType", "ExecutionRatio"
    );

    // Metadata header keys (after the "# Key: value" format)
    private static final String META_DELIVERY_DAY  = "Delivery Day";
    private static final String META_BIDDING_ZONE  = "Bidding Zone";
    private static final String META_TOTAL_MWH     = "Total MWh";
    private static final String META_EXCHANGE       = "EPEX_SPOT"; // fixed for this format

    @Inject
    public EpexCsvAuctionResultParser() {}

    @Override
    public String supportedFormat() {
        return SUPPORTED_FORMAT;
    }

    /**
     * Parse an EPEX DA CSV file into an {@link AuctionResultBatch}.
     * Structural validation is performed during parse.
     *
     * @param csvFile the path to the CSV file; must be readable
     * @return the parsed batch; never null
     * @throws CsvParseException if any structural parsing error is detected
     */
    @Override
    public AuctionResultBatch parse(Path csvFile) {
        String fileRef = csvFile.toString();
        ParseState state = new ParseState();

        try (BufferedReader reader = Files.newBufferedReader(csvFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                state.lineNumber++;
                processLine(state, line, fileRef);
            }
        } catch (IOException e) {
            throw new CsvParseException(
                "Failed to read CSV file: " + fileRef, 0, e.getMessage(), e);
        }

        // Post-parse validation
        if (state.deliveryDay == null) {
            throw new CsvParseException(
                "Missing required metadata 'Delivery Day' in file: " + fileRef, 0, fileRef);
        }
        if (state.biddingZone == null) {
            throw new CsvParseException(
                "Missing required metadata 'Bidding Zone' in file: " + fileRef, 0, fileRef);
        }
        // Total MWh is optional — partial imports (incremental fills) may not report a total
        if (state.exchangeReportedTotalMwh == null) {
            state.exchangeReportedTotalMwh = BigDecimal.ZERO;
        }
        if (!state.headerParsed) {
            throw new CsvParseException(
                "No column header row found in file: " + fileRef, 0, fileRef);
        }

        return new AuctionResultBatch(
            META_EXCHANGE,
            state.biddingZone,
            state.deliveryDay,
            state.exchangeReportedTotalMwh,
            fileRef,
            state.contracts
        );
    }

    // ---------------------------------------------------------------------------
    // Private parsing logic
    // ---------------------------------------------------------------------------

    private void processLine(ParseState state, String line, String fileRef) {
        String trimmed = line.trim();

        // Empty lines are skipped at all stages
        if (trimmed.isEmpty()) {
            return;
        }

        // Metadata comment lines (start with #)
        if (trimmed.startsWith("#")) {
            parseMetadataComment(state, trimmed, fileRef);
            return;
        }

        // First non-comment, non-empty line is the column header
        if (!state.headerParsed) {
            parseColumnHeader(state, trimmed, fileRef);
            return;
        }

        // Data row
        parseDataRow(state, trimmed, fileRef);
    }

    /**
     * Extract key-value pairs from comment lines in the format:
     * {@code # Key: value}
     */
    private void parseMetadataComment(ParseState state, String commentLine, String fileRef) {
        // Strip leading # and any additional # or whitespace
        String content = commentLine.replaceFirst("^#+\\s*", "").trim();
        int colonIdx = content.indexOf(':');
        if (colonIdx < 0) {
            // Not a key-value comment — treat as a title/description comment, ignore
            return;
        }
        String key   = content.substring(0, colonIdx).trim();
        String value = content.substring(colonIdx + 1).trim();

        switch (key) {
            case META_DELIVERY_DAY -> {
                try {
                    state.deliveryDay = LocalDate.parse(value);
                } catch (DateTimeParseException e) {
                    throw new CsvParseException(
                        "Invalid Delivery Day format: '" + value + "' (expected YYYY-MM-DD)",
                        state.lineNumber, value, e);
                }
            }
            case META_BIDDING_ZONE -> state.biddingZone = value;
            case META_TOTAL_MWH -> {
                try {
                    state.exchangeReportedTotalMwh = new BigDecimal(value);
                } catch (NumberFormatException e) {
                    throw new CsvParseException(
                        "Invalid Total MWh value: '" + value + "'",
                        state.lineNumber, value, e);
                }
            }
            default -> { /* Other metadata fields are ignored in v1 */ }
        }
    }

    /**
     * Parse the column header row. Validates all required columns are present.
     * Builds a column-name to index map for subsequent data row parsing.
     */
    private void parseColumnHeader(ParseState state, String headerLine, String fileRef) {
        String[] cols = splitCsvLine(headerLine);
        Map<String, Integer> colIndex = new HashMap<>();
        for (int i = 0; i < cols.length; i++) {
            colIndex.put(cols[i].trim(), i);
        }

        // Validate required columns
        List<String> missing = new ArrayList<>();
        for (String required : REQUIRED_COLUMNS) {
            if (!colIndex.containsKey(required)) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            throw new CsvParseException(
                "Missing required columns in header: " + missing,
                state.lineNumber, headerLine);
        }

        state.colIndex = colIndex;
        state.headerParsed = true;
    }

    /**
     * Parse a single contract data row. Validates types and accumulates contracts.
     * Checks for duplicate ContractIds.
     */
    private void parseDataRow(ParseState state, String dataLine, String fileRef) {
        String[] cols = splitCsvLine(dataLine);

        String contractId    = getColumn(cols, state.colIndex, "ContractId",    state.lineNumber, dataLine);
        String deliveryStart = getColumn(cols, state.colIndex, "DeliveryStart", state.lineNumber, dataLine);
        String deliveryEnd   = getColumn(cols, state.colIndex, "DeliveryEnd",   state.lineNumber, dataLine);
        String priceStr      = getColumn(cols, state.colIndex, "Price",         state.lineNumber, dataLine);
        String volumeStr     = getColumn(cols, state.colIndex, "Volume",        state.lineNumber, dataLine);
        String directionStr  = getColumn(cols, state.colIndex, "Direction",     state.lineNumber, dataLine);
        String blockTypeStr  = getColumn(cols, state.colIndex, "BlockType",     state.lineNumber, dataLine);
        String execRatioStr  = getColumn(cols, state.colIndex, "ExecutionRatio", state.lineNumber, dataLine);

        // ContractId: must not be blank, must not be a duplicate
        if (contractId.isBlank()) {
            throw new CsvParseException(
                "ContractId must not be blank", state.lineNumber, dataLine);
        }
        if (!state.seenContractIds.add(contractId)) {
            throw new CsvParseException(
                "Duplicate ContractId: '" + contractId + "'", state.lineNumber, dataLine);
        }

        // Parse timestamps
        Instant start;
        Instant end;
        try {
            start = Instant.parse(deliveryStart);
        } catch (DateTimeParseException e) {
            throw new CsvParseException(
                "Invalid DeliveryStart: '" + deliveryStart + "' (expected ISO-8601 UTC)",
                state.lineNumber, dataLine, e);
        }
        try {
            end = Instant.parse(deliveryEnd);
        } catch (DateTimeParseException e) {
            throw new CsvParseException(
                "Invalid DeliveryEnd: '" + deliveryEnd + "' (expected ISO-8601 UTC)",
                state.lineNumber, dataLine, e);
        }
        if (!end.isAfter(start)) {
            throw new CsvParseException(
                "DeliveryEnd must be after DeliveryStart", state.lineNumber, dataLine);
        }

        // Parse numerics
        BigDecimal price;
        try {
            price = new BigDecimal(priceStr);
        } catch (NumberFormatException e) {
            throw new CsvParseException(
                "Invalid Price value: '" + priceStr + "'", state.lineNumber, dataLine, e);
        }

        BigDecimal volume;
        try {
            volume = new BigDecimal(volumeStr);
        } catch (NumberFormatException e) {
            throw new CsvParseException(
                "Invalid Volume value: '" + volumeStr + "'", state.lineNumber, dataLine, e);
        }

        // Parse direction (BUY or SELL)
        TradeDirection direction;
        try {
            direction = TradeDirection.valueOf(directionStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new CsvParseException(
                "Invalid Direction: '" + directionStr + "' (expected BUY or SELL)",
                state.lineNumber, dataLine, e);
        }

        // BlockType: empty string is valid (spot/individual interval contract)
        String blockType = blockTypeStr.isBlank() ? null : blockTypeStr.trim();

        // Parse execution ratio
        BigDecimal executionRatio;
        try {
            executionRatio = new BigDecimal(execRatioStr);
        } catch (NumberFormatException e) {
            throw new CsvParseException(
                "Invalid ExecutionRatio value: '" + execRatioStr + "'",
                state.lineNumber, dataLine, e);
        }
        if (executionRatio.compareTo(BigDecimal.ZERO) < 0
                || executionRatio.compareTo(BigDecimal.ONE) > 0) {
            throw new CsvParseException(
                "ExecutionRatio must be in [0, 1]: '" + execRatioStr + "'",
                state.lineNumber, dataLine);
        }

        state.contracts.add(new AuctionResultContract(
            contractId, price, volume, start, end, blockType, executionRatio, direction
        ));
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Split a CSV line on commas. Does not handle quoted fields — EPEX DA CSV does not
     * use quoted fields per S6.2.
     */
    private static String[] splitCsvLine(String line) {
        return line.split(",", -1); // -1 keeps trailing empty tokens
    }

    /**
     * Retrieve a column value by name, trimming whitespace. Validates the column index
     * is within bounds.
     */
    private static String getColumn(String[] cols, Map<String, Integer> colIndex,
                                     String colName, int lineNumber, String rawLine) {
        int idx = colIndex.get(colName);
        if (idx >= cols.length) {
            throw new CsvParseException(
                "Row has fewer columns than header (expected column '" + colName + "' at index " + idx + ")",
                lineNumber, rawLine);
        }
        return cols[idx].trim();
    }

    // ---------------------------------------------------------------------------
    // Internal parse state (mutable, scoped to a single parse() call)
    // ---------------------------------------------------------------------------

    private static final class ParseState {
        int lineNumber = 0;
        LocalDate deliveryDay = null;
        String biddingZone = null;
        BigDecimal exchangeReportedTotalMwh = null;
        boolean headerParsed = false;
        Map<String, Integer> colIndex = Map.of();
        Set<String> seenContractIds = new HashSet<>();
        List<AuctionResultContract> contracts = new ArrayList<>();
    }
}
