package com.power.posval.persistence.adapter;

import com.power.posval.domain.command.AuctionResultBatch;
import com.power.posval.domain.command.AuctionResultContract;
import com.power.posval.domain.exception.CsvParseException;
import com.power.posval.domain.model.TradeDirection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link EpexCsvAuctionResultParser}.
 *
 * <p>Uses {@code @TempDir} to write CSV content to temp files and feed them to the parser.
 * No Spring context, no containers. Pattern #18, S6.2, DA-VOL-01.
 */
class EpexCsvAuctionResultParserTest {

    /** Standard header block for all test CSVs. */
    private static final String STANDARD_HEADER = """
        # EPEX_SPOT DA Auction Results
        # Delivery Day: 2026-09-16
        # Bidding Zone: DE_LU
        # Total MWh: 12450.00
        ContractId,DeliveryStart,DeliveryEnd,Price,Volume,Direction,BlockType,ExecutionRatio
        """;

    @TempDir
    Path tempDir;

    private EpexCsvAuctionResultParser parser;

    @BeforeEach
    void setUp() {
        parser = new EpexCsvAuctionResultParser();
    }

    // ---------------------------------------------------------------------------
    // supportedFormat()
    // ---------------------------------------------------------------------------

    @Test
    void supportedFormat_returnsEpexDaCsvV1() {
        assertEquals("EPEX_DA_CSV_V1", parser.supportedFormat());
    }

    // ---------------------------------------------------------------------------
    // Valid CSV: 96 contracts
    // ---------------------------------------------------------------------------

    @Test
    void validCsv_96Contracts_returnsCorrectBatch() throws IOException {
        String csv = buildCsvWith96Contracts();
        Path file = writeCsv("valid96.csv", csv);

        AuctionResultBatch batch = parser.parse(file);

        assertEquals("EPEX_SPOT", batch.exchange());
        assertEquals("DE_LU", batch.biddingZone());
        assertEquals(LocalDate.of(2026, 9, 16), batch.deliveryDay());
        assertEquals(0, batch.exchangeReportedTotalMwh().compareTo(new BigDecimal("12450.00")));
        assertEquals(96, batch.contracts().size());
    }

    // ---------------------------------------------------------------------------
    // Header metadata extraction
    // ---------------------------------------------------------------------------

    @Test
    void headerMetadata_deliveryDayBiddingZoneTotalMwh_parsedCorrectly() throws IOException {
        String csv = buildCsvWith1Contract();
        Path file = writeCsv("meta.csv", csv);

        AuctionResultBatch batch = parser.parse(file);

        assertEquals(LocalDate.of(2026, 9, 16), batch.deliveryDay());
        assertEquals("DE_LU", batch.biddingZone());
        assertEquals(0, batch.exchangeReportedTotalMwh().compareTo(new BigDecimal("12450.00")));
    }

    // ---------------------------------------------------------------------------
    // Negative clearing price: parsed correctly (not rejected)
    // ---------------------------------------------------------------------------

    @Test
    void negativeClearingPrice_parsedSuccessfully() throws IOException {
        String csv = STANDARD_HEADER +
            "DELU-20260916-0000,2026-09-15T22:00:00Z,2026-09-15T22:15:00Z,-45.20,30.0,BUY,,1.0\n";
        Path file = writeCsv("neg_price.csv", csv);

        AuctionResultBatch batch = parser.parse(file);

        assertEquals(1, batch.contracts().size());
        assertEquals(0, batch.contracts().get(0).priceMwh().compareTo(new BigDecimal("-45.20")));
    }

    // ---------------------------------------------------------------------------
    // BUY and SELL directions parsed correctly
    // ---------------------------------------------------------------------------

    @Test
    void buyDirection_parsedAsTradeDirectionBuy() throws IOException {
        String csv = STANDARD_HEADER +
            "DELU-20260916-0000,2026-09-15T22:00:00Z,2026-09-15T22:15:00Z,45.20,30.0,BUY,,1.0\n";
        AuctionResultBatch batch = parser.parse(writeCsv("buy.csv", csv));
        assertEquals(TradeDirection.BUY, batch.contracts().get(0).direction());
    }

    @Test
    void sellDirection_parsedAsTradeDirectionSell() throws IOException {
        String csv = STANDARD_HEADER +
            "DELU-20260916-0001,2026-09-15T22:00:00Z,2026-09-15T22:15:00Z,44.80,25.0,SELL,,1.0\n";
        AuctionResultBatch batch = parser.parse(writeCsv("sell.csv", csv));
        assertEquals(TradeDirection.SELL, batch.contracts().get(0).direction());
    }

    // ---------------------------------------------------------------------------
    // Block type parsing: BASELOAD sets blockType; empty string sets null
    // ---------------------------------------------------------------------------

    @Test
    void blockType_baseload_parsedCorrectly() throws IOException {
        String csv = STANDARD_HEADER +
            "BL-001,2026-09-15T22:00:00Z,2026-09-16T22:00:00Z,44.00,100.0,BUY,BASELOAD,0.8\n";
        AuctionResultBatch batch = parser.parse(writeCsv("block.csv", csv));
        assertEquals("BASELOAD", batch.contracts().get(0).blockType());
    }

    @Test
    void blockType_emptyString_parsedAsNull() throws IOException {
        String csv = STANDARD_HEADER +
            "DELU-20260916-0000,2026-09-15T22:00:00Z,2026-09-15T22:15:00Z,45.20,30.0,BUY,,1.0\n";
        AuctionResultBatch batch = parser.parse(writeCsv("spot.csv", csv));
        assertNull(batch.contracts().get(0).blockType());
    }

    // ---------------------------------------------------------------------------
    // Execution ratio: 0.5 and 1.0
    // ---------------------------------------------------------------------------

    @Test
    void executionRatio_halfExecution_parsedCorrectly() throws IOException {
        String csv = STANDARD_HEADER +
            "BL-001,2026-09-15T22:00:00Z,2026-09-16T22:00:00Z,44.00,100.0,BUY,BASELOAD,0.5\n";
        AuctionResultBatch batch = parser.parse(writeCsv("ratio05.csv", csv));
        assertEquals(0, batch.contracts().get(0).executionRatio().compareTo(new BigDecimal("0.5")));
    }

    @Test
    void executionRatio_fullExecution_parsedCorrectly() throws IOException {
        String csv = STANDARD_HEADER +
            "DELU-20260916-0000,2026-09-15T22:00:00Z,2026-09-15T22:15:00Z,45.20,30.0,BUY,,1.0\n";
        AuctionResultBatch batch = parser.parse(writeCsv("ratio10.csv", csv));
        assertEquals(0, batch.contracts().get(0).executionRatio().compareTo(new BigDecimal("1.0")));
    }

    // ---------------------------------------------------------------------------
    // Missing column: CsvParseException thrown
    // ---------------------------------------------------------------------------

    @Test
    void missingColumn_throwsCsvParseException() throws IOException {
        // Remove "ExecutionRatio" from the header
        String csv = """
            # EPEX_SPOT DA Auction Results
            # Delivery Day: 2026-09-16
            # Bidding Zone: DE_LU
            # Total MWh: 12450.00
            ContractId,DeliveryStart,DeliveryEnd,Price,Volume,Direction,BlockType
            DELU-20260916-0000,2026-09-15T22:00:00Z,2026-09-15T22:15:00Z,45.20,30.0,BUY,
            """;
        Path file = writeCsv("missing_col.csv", csv);

        assertThrows(CsvParseException.class, () -> parser.parse(file));
    }

    // ---------------------------------------------------------------------------
    // Malformed number: CsvParseException with line number information
    // ---------------------------------------------------------------------------

    @Test
    void malformedPrice_throwsCsvParseExceptionWithLineNumber() throws IOException {
        String csv = STANDARD_HEADER +
            "DELU-20260916-0000,2026-09-15T22:00:00Z,2026-09-15T22:15:00Z,NOT_A_NUMBER,30.0,BUY,,1.0\n";
        Path file = writeCsv("bad_price.csv", csv);

        CsvParseException ex = assertThrows(CsvParseException.class, () -> parser.parse(file));
        assertTrue(ex.lineNumber() > 0, "Exception should carry a 1-based line number");
        assertNotNull(ex.detail(), "Exception should carry a detail string");
    }

    @Test
    void malformedVolume_throwsCsvParseException() throws IOException {
        String csv = STANDARD_HEADER +
            "DELU-20260916-0000,2026-09-15T22:00:00Z,2026-09-15T22:15:00Z,45.20,INVALID,BUY,,1.0\n";
        assertThrows(CsvParseException.class, () -> parser.parse(writeCsv("bad_vol.csv", csv)));
    }

    @Test
    void malformedDeliveryStart_throwsCsvParseException() throws IOException {
        String csv = STANDARD_HEADER +
            "DELU-20260916-0000,NOT-A-TIMESTAMP,2026-09-15T22:15:00Z,45.20,30.0,BUY,,1.0\n";
        assertThrows(CsvParseException.class, () -> parser.parse(writeCsv("bad_ts.csv", csv)));
    }

    @Test
    void malformedDirection_throwsCsvParseException() throws IOException {
        String csv = STANDARD_HEADER +
            "DELU-20260916-0000,2026-09-15T22:00:00Z,2026-09-15T22:15:00Z,45.20,30.0,HOLD,,1.0\n";
        assertThrows(CsvParseException.class, () -> parser.parse(writeCsv("bad_dir.csv", csv)));
    }

    // ---------------------------------------------------------------------------
    // Duplicate ContractId: CsvParseException thrown
    // ---------------------------------------------------------------------------

    @Test
    void duplicateContractId_throwsCsvParseException() throws IOException {
        String csv = STANDARD_HEADER +
            "DELU-20260916-0000,2026-09-15T22:00:00Z,2026-09-15T22:15:00Z,45.20,30.0,BUY,,1.0\n" +
            "DELU-20260916-0000,2026-09-15T22:15:00Z,2026-09-15T22:30:00Z,44.80,28.0,BUY,,1.0\n";
        Path file = writeCsv("dup_id.csv", csv);

        CsvParseException ex = assertThrows(CsvParseException.class, () -> parser.parse(file));
        assertTrue(ex.getMessage().toLowerCase().contains("duplicate")
            || ex.detail().contains("DELU-20260916-0000"),
            "Exception should mention duplicate ContractId");
    }

    // ---------------------------------------------------------------------------
    // Missing Delivery Day metadata: CsvParseException
    // ---------------------------------------------------------------------------

    @Test
    void missingDeliveryDayMetadata_throwsCsvParseException() throws IOException {
        String csv = """
            # EPEX_SPOT DA Auction Results
            # Bidding Zone: DE_LU
            # Total MWh: 12450.00
            ContractId,DeliveryStart,DeliveryEnd,Price,Volume,Direction,BlockType,ExecutionRatio
            DELU-20260916-0000,2026-09-15T22:00:00Z,2026-09-15T22:15:00Z,45.20,30.0,BUY,,1.0
            """;
        assertThrows(CsvParseException.class, () -> parser.parse(writeCsv("no_day.csv", csv)));
    }

    // ---------------------------------------------------------------------------
    // Missing Bidding Zone metadata: CsvParseException
    // ---------------------------------------------------------------------------

    @Test
    void missingBiddingZoneMetadata_throwsCsvParseException() throws IOException {
        String csv = """
            # EPEX_SPOT DA Auction Results
            # Delivery Day: 2026-09-16
            # Total MWh: 12450.00
            ContractId,DeliveryStart,DeliveryEnd,Price,Volume,Direction,BlockType,ExecutionRatio
            DELU-20260916-0000,2026-09-15T22:00:00Z,2026-09-15T22:15:00Z,45.20,30.0,BUY,,1.0
            """;
        assertThrows(CsvParseException.class, () -> parser.parse(writeCsv("no_zone.csv", csv)));
    }

    // ---------------------------------------------------------------------------
    // DeliveryEnd <= DeliveryStart: CsvParseException
    // ---------------------------------------------------------------------------

    @Test
    void deliveryEndNotAfterStart_throwsCsvParseException() throws IOException {
        String csv = STANDARD_HEADER +
            // end == start
            "DELU-20260916-0000,2026-09-15T22:15:00Z,2026-09-15T22:00:00Z,45.20,30.0,BUY,,1.0\n";
        assertThrows(CsvParseException.class, () -> parser.parse(writeCsv("bad_range.csv", csv)));
    }

    // ---------------------------------------------------------------------------
    // ExecutionRatio out of [0, 1]: CsvParseException
    // ---------------------------------------------------------------------------

    @Test
    void executionRatioAbove1_throwsCsvParseException() throws IOException {
        String csv = STANDARD_HEADER +
            "DELU-20260916-0000,2026-09-15T22:00:00Z,2026-09-15T22:15:00Z,45.20,30.0,BUY,,1.5\n";
        assertThrows(CsvParseException.class, () -> parser.parse(writeCsv("ratio_bad.csv", csv)));
    }

    // ---------------------------------------------------------------------------
    // File reference is preserved in the batch
    // ---------------------------------------------------------------------------

    @Test
    void fileReference_preservedInBatch() throws IOException {
        String csv = buildCsvWith1Contract();
        Path file = writeCsv("reference_test.csv", csv);

        AuctionResultBatch batch = parser.parse(file);

        assertEquals(file.toString(), batch.fileReference());
    }

    // ---------------------------------------------------------------------------
    // CRLF line endings: parsed without error
    // ---------------------------------------------------------------------------

    @Test
    void crlfLineEndings_parsedSuccessfully() throws IOException {
        // Replace \n with \r\n
        String csv = buildCsvWith1Contract().replace("\n", "\r\n");
        Path file = writeCsvBytes("crlf.csv", csv.getBytes(StandardCharsets.UTF_8));

        assertDoesNotThrow(() -> parser.parse(file));
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private Path writeCsv(String filename, String content) throws IOException {
        Path file = tempDir.resolve(filename);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private Path writeCsvBytes(String filename, byte[] content) throws IOException {
        Path file = tempDir.resolve(filename);
        Files.write(file, content);
        return file;
    }

    /** Build a single-contract CSV for metadata tests. */
    private static String buildCsvWith1Contract() {
        return STANDARD_HEADER +
            "DELU-20260916-0000,2026-09-15T22:00:00Z,2026-09-15T22:15:00Z,45.20,30.0,BUY,,1.0\n";
    }

    /** Build a 96-contract CSV where each 15-min interval of 2026-09-16 has one contract. */
    private static String buildCsvWith96Contracts() {
        StringBuilder sb = new StringBuilder(STANDARD_HEADER);
        // 2026-09-16 in CET starts at 2026-09-15T22:00:00Z
        java.time.Instant cursor = java.time.Instant.parse("2026-09-15T22:00:00Z");
        for (int i = 0; i < 96; i++) {
            java.time.Instant end = cursor.plusSeconds(900);
            // Instant.toString() already returns ISO-8601 with trailing Z
            sb.append(String.format(
                "DELU-20260916-%04d,%s,%s,45.20,30.0,BUY,,1.0%n",
                i, cursor, end));
            cursor = end;
        }
        return sb.toString();
    }
}
