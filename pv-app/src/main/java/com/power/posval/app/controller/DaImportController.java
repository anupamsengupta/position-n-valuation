package com.power.posval.app.controller;

import com.power.posval.app.dto.ApiResponse;
import com.power.posval.app.dto.DaImportRequest;
import com.power.posval.app.dto.DaImportSessionDto;
import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.model.AuctionImportSession;
import com.power.posval.domain.port.service.AuctionImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Simulator REST controller for DA auction result import operations (S9.4).
 *
 * <p>All mutation endpoints delegate to {@link AuctionImportService} via
 * {@link TransactionalExecutor} so that the import orchestrator runs within
 * a single {@code UnitOfWork} (S10.3, Pattern #24). Read endpoints are
 * non-transactional by convention.
 *
 * <p>This controller is simulator-scope ({@code pv-app} only). D-14.
 */
@RestController
@RequestMapping("/api/da/import")
public class DaImportController {

    private static final Logger log = LoggerFactory.getLogger(DaImportController.class);

    private final AuctionImportService auctionImportService;
    private final TransactionalExecutor txExecutor;

    public DaImportController(AuctionImportService auctionImportService,
                               TransactionalExecutor txExecutor) {
        this.auctionImportService = auctionImportService;
        this.txExecutor = txExecutor;
    }

    /**
     * Import a parsed auction result batch supplied as JSON (S9.4).
     *
     * <p>{@code POST /api/da/import}
     */
    @PostMapping
    public ApiResponse<DaImportSessionDto> importBatch(
            @RequestBody DaImportRequest request) {
        log.info("POST /api/da/import exchange={} zone={} day={}",
            request.exchange(), request.biddingZone(), request.deliveryDay());
        AuctionImportSession session = txExecutor.execute(
            () -> auctionImportService.importAuctionResults(request.toCommand()));
        log.info("POST /api/da/import => sessionId={} status={}",
            session.sessionId(), session.status());
        return ApiResponse.ok(toDto(session));
    }

    /**
     * Retrieve an import session by its UUID business key (S9.4).
     *
     * <p>{@code GET /api/da/import/{id}}
     */
    @GetMapping("/{id}")
    public ApiResponse<DaImportSessionDto> getSession(
            @PathVariable String id,
            @RequestParam(defaultValue = "default") String tenantId) {
        log.info("GET /api/da/import/{} tenantId={}", id, tenantId);
        Optional<AuctionImportSession> session = txExecutor.execute(
            () -> auctionImportService.getImportSession(tenantId, UUID.fromString(id)));
        return session
            .map(s -> ApiResponse.ok(toDto(s)))
            .orElse(ApiResponse.error("Session not found: " + id));
    }

    /**
     * Import a DA auction result CSV file via multipart upload (S9.4).
     *
     * <p>{@code POST /api/da/import/file}
     *
     * <p>The uploaded file is written to a temporary location, then handed to
     * {@code AuctionImportService.importFromFile()}. The temp file is deleted
     * after the import completes.
     */
    @PostMapping("/file")
    public ApiResponse<DaImportSessionDto> importFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "default") String tenantId) throws IOException {
        log.info("POST /api/da/import/file name={} tenantId={}", file.getOriginalFilename(), tenantId);
        Path tempFile = Files.createTempFile("da-import-", "-" + file.getOriginalFilename());
        try {
            file.transferTo(tempFile.toFile());
            AuctionImportSession session = txExecutor.execute(
                () -> auctionImportService.importFromFile(tenantId, tempFile));
            log.info("POST /api/da/import/file => sessionId={} status={}",
                session.sessionId(), session.status());
            return ApiResponse.ok(toDto(session));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * Retrieve the import session history for an exchange and bidding zone (S9.4).
     *
     * <p>{@code GET /api/da/import/history?tenantId=...&exchange=...&biddingZone=...}
     */
    @GetMapping("/history")
    public ApiResponse<List<DaImportSessionDto>> getHistory(
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestParam String exchange,
            @RequestParam String biddingZone) {
        log.info("GET /api/da/import/history tenantId={} exchange={} zone={}",
            tenantId, exchange, biddingZone);
        List<AuctionImportSession> history = txExecutor.execute(
            () -> auctionImportService.getImportHistory(tenantId, exchange, biddingZone));
        List<DaImportSessionDto> dtos = history.stream().map(this::toDto).toList();
        return ApiResponse.ok(dtos);
    }

    private DaImportSessionDto toDto(AuctionImportSession s) {
        return new DaImportSessionDto(
            s.sessionId(),
            s.tenantId(),
            s.exchange(),
            s.biddingZone(),
            s.deliveryDay(),
            s.importTimestamp(),
            s.status().name(),
            s.exchangeReportedTotalMwh(),
            s.importedTotalMwh(),
            s.intervalCount(),
            s.fileReference(),
            s.tradeIds(),
            s.validationErrors(),
            s.createdAt(),
            s.completedAt()
        );
    }
}
