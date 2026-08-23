package com.power.posval.domain.service.da;

import com.power.posval.domain.command.AuctionResultBatch;
import com.power.posval.domain.command.AuctionResultContract;
import com.power.posval.domain.command.ImportAuctionResults;
import com.power.posval.domain.exception.CsvParseException;
import com.power.posval.domain.model.AlertCategory;
import com.power.posval.domain.model.AlertSeverity;
import com.power.posval.domain.model.AlertStatus;
import com.power.posval.domain.model.AuctionImportSession;
import com.power.posval.domain.model.AuctionImportStatus;
import com.power.posval.domain.model.OperationalAlert;
import com.power.posval.domain.model.TradeDirection;
import com.power.posval.domain.model.value.AuctionFolderPollerConfig;
import com.power.posval.domain.port.AuctionResultParser;
import com.power.posval.domain.port.repository.OperationalAlertRepository;
import com.power.posval.domain.port.service.AuctionImportService;
import com.power.posval.domain.port.service.OperationalAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DefaultAuctionFolderPoller}.
 *
 * <p>Uses {@code @TempDir} for real file-system operations (inbox, processed, failed
 * directories). All domain ports are hand-mocked.
 * Pattern #18, S6.3, DA-VOL-01.
 */
class DefaultAuctionFolderPollerTest {

    private static final String TENANT = "TN_0042";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 16);

    @TempDir
    Path tempRoot;

    private Path inbox;
    private Path processed;
    private Path failed;

    private final List<Path>             parsedFiles      = new ArrayList<>();
    private final List<ImportAuctionResults> importedCmds = new ArrayList<>();
    private final List<OperationalAlert> raisedAlerts     = new ArrayList<>();
    private final List<Object>           publishedEvents  = new ArrayList<>();

    /** Controls whether the stub parser succeeds or throws. */
    private boolean parserShouldThrow = false;

    private DefaultAuctionFolderPoller poller;

    @BeforeEach
    void setUp() throws IOException {
        parsedFiles.clear();
        importedCmds.clear();
        raisedAlerts.clear();
        publishedEvents.clear();
        parserShouldThrow = false;

        inbox     = tempRoot.resolve("inbox");
        processed = tempRoot.resolve("processed");
        failed    = tempRoot.resolve("failed");

        Files.createDirectories(inbox);
        Files.createDirectories(processed);
        Files.createDirectories(failed);

        AuctionResultParser parser = new AuctionResultParser() {
            @Override
            public AuctionResultBatch parse(Path file) {
                parsedFiles.add(file);
                if (parserShouldThrow) {
                    throw new CsvParseException("Stub parse error", 5, "bad-line");
                }
                return makeBatch(TENANT);
            }
            @Override public String supportedFormat() { return "EPEX_DA_CSV_V1"; }
        };

        AuctionImportService importService = new AuctionImportService() {
            @Override
            public AuctionImportSession importAuctionResults(ImportAuctionResults cmd) {
                importedCmds.add(cmd);
                return makeImportedSession();
            }

            @Override
            public AuctionImportSession importFromFile(String tenantId, Path csvFile) {
                return importAuctionResults(new ImportAuctionResults(tenantId, makeBatch(tenantId)));
            }

            @Override
            public Optional<AuctionImportSession> getImportSession(String tenantId, UUID sessionId) {
                return Optional.empty();
            }

            @Override
            public List<AuctionImportSession> getImportHistory(String tenantId, String exchange,
                    String biddingZone) {
                return List.of();
            }
        };

        OperationalAlertRepository alertRepo = new OperationalAlertRepository() {
            @Override public void save(OperationalAlert a) { raisedAlerts.add(a); }
            @Override public List<OperationalAlert> findOpen(String t) { return List.of(); }
            @Override public List<OperationalAlert> findByCategory(String t, AlertCategory c, AlertStatus s) { return List.of(); }
            @Override public List<OperationalAlert> findByDeliveryDay(String t, LocalDate d) { return List.of(); }
            @Override public void acknowledge(String t, UUID id, String u, Instant at) {}
            @Override public void resolve(String t, UUID id, Instant at) {}
            @Override public Map<AlertCategory, Long> countByStatus(String t, AlertStatus s) { return Map.of(); }
        };
        OperationalAlertService alertService =
            new DefaultOperationalAlertService(alertRepo, publishedEvents::add);

        AuctionFolderPollerConfig config = new AuctionFolderPollerConfig(
            inbox, processed, failed,
            Duration.ofSeconds(30),
            "*.csv",
            TENANT
        );

        poller = new DefaultAuctionFolderPoller(parser, importService, alertService, config);
    }

    // ---------------------------------------------------------------------------
    // Empty inbox → returns empty list
    // ---------------------------------------------------------------------------

    @Test
    void emptyInbox_returnsEmptyList() {
        List<AuctionImportSession> results = poller.pollOnce();
        assertTrue(results.isEmpty(), "Empty inbox should produce empty result list");
    }

    // ---------------------------------------------------------------------------
    // Successful parse → file moved to processed, session returned
    // ---------------------------------------------------------------------------

    @Test
    void successfulParse_fileMovedToProcessed_sessionReturned() throws IOException {
        Path csvFile = createCsvFile("EPEX_SPOT_DE_LU_20260916.csv");

        List<AuctionImportSession> results = poller.pollOnce();

        assertEquals(1, results.size());
        assertEquals(AuctionImportStatus.IMPORTED, results.get(0).status());

        // File must have been moved out of inbox
        assertFalse(Files.exists(csvFile), "File should be moved out of inbox");

        // File must appear in the processed/{deliveryDay}/ subdirectory
        Path expectedProcessed = processed.resolve(DAY.toString())
            .resolve("EPEX_SPOT_DE_LU_20260916.csv");
        assertTrue(Files.exists(expectedProcessed), "File should be in processed directory");
    }

    // ---------------------------------------------------------------------------
    // Failed parse → file moved to failed, alert raised
    // ---------------------------------------------------------------------------

    @Test
    void failedParse_fileMovedToFailed_alertRaised() throws IOException {
        Path csvFile = createCsvFile("EPEX_SPOT_DE_LU_20260916.csv");
        parserShouldThrow = true;

        List<AuctionImportSession> results = poller.pollOnce();

        assertTrue(results.isEmpty(), "Failed parse should return empty result list");

        // File must have been moved to failed directory
        assertFalse(Files.exists(csvFile), "File should be moved out of inbox on failure");
        // The failed dir should contain the file
        boolean foundInFailed = Files.walk(failed)
            .anyMatch(p -> p.getFileName().toString().equals("EPEX_SPOT_DE_LU_20260916.csv"));
        assertTrue(foundInFailed, "File should be in the failed directory");

        // An operational alert must be raised
        assertFalse(raisedAlerts.isEmpty(), "An alert should be raised on parse failure");
        assertTrue(raisedAlerts.stream()
            .anyMatch(a -> a.category() == AlertCategory.AUCTION_INGESTION
                        && a.severity() == AlertSeverity.CRITICAL),
            "Expected CRITICAL AUCTION_INGESTION alert for parse failure");
    }

    // ---------------------------------------------------------------------------
    // Multiple files processed in filename order
    // ---------------------------------------------------------------------------

    @Test
    void multipleFiles_processedInFilenameOrder() throws IOException {
        // Create files out of alphabetical order to verify deterministic ordering
        createCsvFile("EPEX_SPOT_DE_LU_20260918.csv");
        createCsvFile("EPEX_SPOT_DE_LU_20260916.csv");
        createCsvFile("EPEX_SPOT_DE_LU_20260917.csv");

        poller.pollOnce();

        // Verify all 3 files were parsed
        assertEquals(3, parsedFiles.size());

        // Verify alphabetical filename order
        List<String> fileNames = parsedFiles.stream()
            .map(p -> p.getFileName().toString())
            .toList();
        assertEquals("EPEX_SPOT_DE_LU_20260916.csv", fileNames.get(0));
        assertEquals("EPEX_SPOT_DE_LU_20260917.csv", fileNames.get(1));
        assertEquals("EPEX_SPOT_DE_LU_20260918.csv", fileNames.get(2));
    }

    // ---------------------------------------------------------------------------
    // Non-CSV files in inbox are ignored
    // ---------------------------------------------------------------------------

    @Test
    void nonCsvFiles_areIgnored() throws IOException {
        createFile("README.txt", "not a csv");
        createFile("data.json", "{}");
        createCsvFile("EPEX_SPOT_DE_LU_20260916.csv");

        poller.pollOnce();

        // Only 1 CSV file parsed
        assertEquals(1, parsedFiles.size());
    }

    // ---------------------------------------------------------------------------
    // Lifecycle: start/stop/isRunning
    // ---------------------------------------------------------------------------

    @Test
    void lifecycle_startAndStop() {
        assertFalse(poller.isRunning(), "Poller should not be running initially");
        poller.start();
        assertTrue(poller.isRunning());
        poller.stop();
        assertFalse(poller.isRunning());
    }

    // ---------------------------------------------------------------------------
    // Missing inbox directory → returns empty list without throwing
    // ---------------------------------------------------------------------------

    @Test
    void missingInboxDirectory_returnsEmptyList() throws IOException {
        // Replace config with a non-existent inbox
        Path nonExistent = tempRoot.resolve("nonexistent-inbox");
        AuctionFolderPollerConfig cfg = new AuctionFolderPollerConfig(
            nonExistent, processed, failed, Duration.ofSeconds(30), "*.csv", TENANT);
        AuctionImportService noOpImportService = new AuctionImportService() {
            @Override public AuctionImportSession importAuctionResults(ImportAuctionResults c) { return makeImportedSession(); }
            @Override public AuctionImportSession importFromFile(String t, Path f) { return makeImportedSession(); }
            @Override public Optional<AuctionImportSession> getImportSession(String t, UUID id) { return Optional.empty(); }
            @Override public List<AuctionImportSession> getImportHistory(String t, String ex, String z) { return List.of(); }
        };
        AuctionResultParser noOpParser = new AuctionResultParser() {
            @Override public AuctionResultBatch parse(Path f) { return makeBatch(TENANT); }
            @Override public String supportedFormat() { return "EPEX_DA_CSV_V1"; }
        };
        DefaultAuctionFolderPoller pollerWithMissingInbox =
            new DefaultAuctionFolderPoller(
                noOpParser,
                noOpImportService,
                new DefaultOperationalAlertService(
                    new OperationalAlertRepository() {
                        @Override public void save(OperationalAlert a) {}
                        @Override public List<OperationalAlert> findOpen(String t) { return List.of(); }
                        @Override public List<OperationalAlert> findByCategory(String t, AlertCategory c, AlertStatus s) { return List.of(); }
                        @Override public List<OperationalAlert> findByDeliveryDay(String t, LocalDate d) { return List.of(); }
                        @Override public void acknowledge(String t, UUID id, String u, Instant at) {}
                        @Override public void resolve(String t, UUID id, Instant at) {}
                        @Override public Map<AlertCategory, Long> countByStatus(String t, AlertStatus s) { return Map.of(); }
                    },
                    e -> {}
                ),
                cfg);

        List<AuctionImportSession> results = assertDoesNotThrow(pollerWithMissingInbox::pollOnce);
        assertTrue(results.isEmpty());
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private Path createCsvFile(String filename) throws IOException {
        Path file = inbox.resolve(filename);
        Files.writeString(file, "# dummy csv content");
        return file;
    }

    private Path createFile(String filename, String content) throws IOException {
        Path file = inbox.resolve(filename);
        Files.writeString(file, content);
        return file;
    }

    private static AuctionResultBatch makeBatch(String tenantId) {
        Instant start = DAY.atStartOfDay(ZoneId.of("Europe/Berlin")).toInstant();
        Instant end   = start.plusSeconds(900);
        return new AuctionResultBatch(
            "EPEX_SPOT", "DE_LU", DAY, new BigDecimal("0.25"), "test.csv",
            List.of(new AuctionResultContract(
                "C-0001", new BigDecimal("45.0"), new BigDecimal("1.0"),
                start, end, null, BigDecimal.ONE, TradeDirection.BUY
            ))
        );
    }

    private static AuctionImportSession makeImportedSession() {
        return AuctionImportSession.builder()
            .sessionId(UUID.randomUUID())
            .tenantId(TENANT)
            .exchange("EPEX_SPOT")
            .biddingZone("DE_LU")
            .deliveryDay(DAY)
            .importTimestamp(Instant.now())
            .status(AuctionImportStatus.IMPORTED)
            .exchangeReportedTotalMwh(new BigDecimal("0.25"))
            .intervalCount(96)
            .fileReference("test.csv")
            .createdAt(Instant.now())
            .completedAt(Instant.now())
            .build();
    }
}
