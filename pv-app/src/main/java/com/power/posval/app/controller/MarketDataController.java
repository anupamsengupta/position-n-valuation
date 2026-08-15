package com.power.posval.app.controller;

import com.power.posval.app.dto.ApiResponse;
import com.power.posval.app.dto.MarketDataRequest;
import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.service.MarketDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;

@RestController
@RequestMapping("/api/market-data")
public class MarketDataController {

    private static final Logger log = LoggerFactory.getLogger(MarketDataController.class);

    private final MarketDataService marketDataService;
    private final TransactionalExecutor txExecutor;

    public MarketDataController(MarketDataService marketDataService,
                                 TransactionalExecutor txExecutor) {
        this.marketDataService = marketDataService;
        this.txExecutor = txExecutor;
    }

    @GetMapping("/fixings")
    public ApiResponse<MarketDataLookup> findFixing(
            @RequestParam String tenantId,
            @RequestParam String series,
            @RequestParam String intervalStart) {
        log.info("GET /api/market-data/fixings tenantId={} series={} intervalStart={}", tenantId, series, intervalStart);
        Optional<MarketDataLookup> result = txExecutor.execute(
                () -> marketDataService.findFixing(tenantId, series, Instant.parse(intervalStart)));
        log.info("GET /api/market-data/fixings => found={}", result.isPresent());
        return result.map(ApiResponse::ok)
                .orElse(ApiResponse.error("Fixing not found"));
    }

    @PostMapping("/fixings")
    public ApiResponse<String> saveFixing(@RequestBody MarketDataRequest request) {
        log.info("POST /api/market-data/fixings tenantId={} series={} intervalStart={}",
            request.tenantId(), request.series(), request.intervalStart());
        txExecutor.run(() -> marketDataService.saveFixing(
                request.tenantId(), request.series(),
                Instant.parse(request.intervalStart()), request.toLookup()));
        log.info("POST /api/market-data/fixings => saved");
        return ApiResponse.ok("saved");
    }

    @GetMapping("/forward-curves")
    public ApiResponse<MarketDataLookup> findForwardCurve(
            @RequestParam String tenantId,
            @RequestParam String series,
            @RequestParam String pillar,
            @RequestParam String asOfDate) {
        log.info("GET /api/market-data/forward-curves tenantId={} series={} pillar={} asOfDate={}",
            tenantId, series, pillar, asOfDate);
        Optional<MarketDataLookup> result = txExecutor.execute(
                () -> marketDataService.findForwardCurve(tenantId, series,
                        YearMonth.parse(pillar), Instant.parse(asOfDate)));
        log.info("GET /api/market-data/forward-curves => found={}", result.isPresent());
        return result.map(ApiResponse::ok)
                .orElse(ApiResponse.error("Forward curve not found"));
    }

    @PostMapping("/forward-curves")
    public ApiResponse<String> saveForwardCurve(@RequestBody MarketDataRequest request) {
        log.info("POST /api/market-data/forward-curves tenantId={} series={} pillar={} asOfDate={}",
            request.tenantId(), request.series(), request.pillar(), request.asOfDate());
        txExecutor.run(() -> marketDataService.saveForwardCurve(
                request.tenantId(), request.series(),
                YearMonth.parse(request.pillar()),
                Instant.parse(request.asOfDate()), request.toLookup()));
        log.info("POST /api/market-data/forward-curves => saved");
        return ApiResponse.ok("saved");
    }

    @GetMapping("/indices")
    public ApiResponse<MarketDataLookup> findIndex(
            @RequestParam String tenantId,
            @RequestParam String series,
            @RequestParam String refMonth) {
        log.info("GET /api/market-data/indices tenantId={} series={} refMonth={}", tenantId, series, refMonth);
        Optional<MarketDataLookup> result = txExecutor.execute(
                () -> marketDataService.findIndex(tenantId, series, refMonth));
        log.info("GET /api/market-data/indices => found={}", result.isPresent());
        return result.map(ApiResponse::ok)
                .orElse(ApiResponse.error("Index not found"));
    }

    @PostMapping("/indices")
    public ApiResponse<String> saveIndex(@RequestBody MarketDataRequest request) {
        log.info("POST /api/market-data/indices tenantId={} series={} refMonth={}",
            request.tenantId(), request.series(), request.refMonth());
        txExecutor.run(() -> marketDataService.saveIndex(
                request.tenantId(), request.series(),
                request.refMonth(), request.toLookup()));
        log.info("POST /api/market-data/indices => saved");
        return ApiResponse.ok("saved");
    }

    @GetMapping("/fx-rates")
    public ApiResponse<MarketDataLookup> findFxRate(
            @RequestParam String tenantId,
            @RequestParam String pair,
            @RequestParam String referenceDate) {
        log.info("GET /api/market-data/fx-rates tenantId={} pair={} referenceDate={}", tenantId, pair, referenceDate);
        Optional<MarketDataLookup> result = txExecutor.execute(
                () -> marketDataService.findFxRate(tenantId, pair, Instant.parse(referenceDate)));
        log.info("GET /api/market-data/fx-rates => found={}", result.isPresent());
        return result.map(ApiResponse::ok)
                .orElse(ApiResponse.error("FX rate not found"));
    }

    @PostMapping("/fx-rates")
    public ApiResponse<String> saveFxRate(@RequestBody MarketDataRequest request) {
        log.info("POST /api/market-data/fx-rates tenantId={} pair={} referenceDate={}",
            request.tenantId(), request.currencyPair(), request.referenceDate());
        txExecutor.run(() -> marketDataService.saveFxRate(
                request.tenantId(), request.currencyPair(),
                Instant.parse(request.referenceDate()), request.toLookup()));
        log.info("POST /api/market-data/fx-rates => saved");
        return ApiResponse.ok("saved");
    }
}
