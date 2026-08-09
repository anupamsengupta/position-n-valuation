package com.power.posval.app.controller;

import com.power.posval.app.dto.ApiResponse;
import com.power.posval.app.dto.MarketDataRequest;
import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.port.marketdata.MarketDataLookup;
import com.power.posval.domain.port.service.MarketDataService;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;

@RestController
@RequestMapping("/api/market-data")
public class MarketDataController {

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
        Optional<MarketDataLookup> result = txExecutor.execute(
                () -> marketDataService.findFixing(tenantId, series, Instant.parse(intervalStart)));
        return result.map(ApiResponse::ok)
                .orElse(ApiResponse.error("Fixing not found"));
    }

    @PostMapping("/fixings")
    public ApiResponse<String> saveFixing(@RequestBody MarketDataRequest request) {
        txExecutor.run(() -> marketDataService.saveFixing(
                request.tenantId(), request.series(),
                Instant.parse(request.intervalStart()), request.toLookup()));
        return ApiResponse.ok("saved");
    }

    @GetMapping("/forward-curves")
    public ApiResponse<MarketDataLookup> findForwardCurve(
            @RequestParam String tenantId,
            @RequestParam String series,
            @RequestParam String pillar,
            @RequestParam String asOfDate) {
        Optional<MarketDataLookup> result = txExecutor.execute(
                () -> marketDataService.findForwardCurve(tenantId, series,
                        YearMonth.parse(pillar), Instant.parse(asOfDate)));
        return result.map(ApiResponse::ok)
                .orElse(ApiResponse.error("Forward curve not found"));
    }

    @PostMapping("/forward-curves")
    public ApiResponse<String> saveForwardCurve(@RequestBody MarketDataRequest request) {
        txExecutor.run(() -> marketDataService.saveForwardCurve(
                request.tenantId(), request.series(),
                YearMonth.parse(request.pillar()),
                Instant.parse(request.asOfDate()), request.toLookup()));
        return ApiResponse.ok("saved");
    }

    @GetMapping("/indices")
    public ApiResponse<MarketDataLookup> findIndex(
            @RequestParam String tenantId,
            @RequestParam String series,
            @RequestParam String refMonth) {
        Optional<MarketDataLookup> result = txExecutor.execute(
                () -> marketDataService.findIndex(tenantId, series, refMonth));
        return result.map(ApiResponse::ok)
                .orElse(ApiResponse.error("Index not found"));
    }

    @PostMapping("/indices")
    public ApiResponse<String> saveIndex(@RequestBody MarketDataRequest request) {
        txExecutor.run(() -> marketDataService.saveIndex(
                request.tenantId(), request.series(),
                request.refMonth(), request.toLookup()));
        return ApiResponse.ok("saved");
    }

    @GetMapping("/fx-rates")
    public ApiResponse<MarketDataLookup> findFxRate(
            @RequestParam String tenantId,
            @RequestParam String pair,
            @RequestParam String referenceDate) {
        Optional<MarketDataLookup> result = txExecutor.execute(
                () -> marketDataService.findFxRate(tenantId, pair, Instant.parse(referenceDate)));
        return result.map(ApiResponse::ok)
                .orElse(ApiResponse.error("FX rate not found"));
    }

    @PostMapping("/fx-rates")
    public ApiResponse<String> saveFxRate(@RequestBody MarketDataRequest request) {
        txExecutor.run(() -> marketDataService.saveFxRate(
                request.tenantId(), request.currencyPair(),
                Instant.parse(request.referenceDate()), request.toLookup()));
        return ApiResponse.ok("saved");
    }
}
