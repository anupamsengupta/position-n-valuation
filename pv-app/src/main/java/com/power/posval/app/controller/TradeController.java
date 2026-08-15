package com.power.posval.app.controller;

import com.power.posval.app.dto.*;
import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.service.TradeAmendHandler;
import com.power.posval.domain.service.TradeCancelHandler;
import com.power.posval.domain.service.TradeCaptureHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/trades")
public class TradeController {

    private static final Logger log = LoggerFactory.getLogger(TradeController.class);

    private final TradeCaptureHandler captureHandler;
    private final TradeAmendHandler amendHandler;
    private final TradeCancelHandler cancelHandler;
    private final TransactionalExecutor txExecutor;

    public TradeController(TradeCaptureHandler captureHandler,
                            TradeAmendHandler amendHandler,
                            TradeCancelHandler cancelHandler,
                            TransactionalExecutor txExecutor) {
        this.captureHandler = captureHandler;
        this.amendHandler = amendHandler;
        this.cancelHandler = cancelHandler;
        this.txExecutor = txExecutor;
    }

    @PostMapping("/capture")
    public ApiResponse<List<PositionLedgerEntryDto>> capture(@RequestBody TradeCaptureRequest request) {
        log.info("POST /api/trades/capture tradeId={} tradeLegId={} tenantId={}", request.tradeId(), request.tradeLegId(), request.tenantId());
        List<PositionLedgerEntry> entries = txExecutor.execute(
                () -> captureHandler.handle(request.toCommand()));
        log.info("POST /api/trades/capture => {} position entries created", entries.size());
        return ApiResponse.ok(entries.stream().map(PositionLedgerEntryDto::from).toList());
    }

    @PostMapping("/amend")
    public ApiResponse<List<PositionLedgerEntryDto>> amend(@RequestBody TradeAmendRequest request) {
        log.info("POST /api/trades/amend tradeId={}", request.tradeId());
        List<PositionLedgerEntry> entries = txExecutor.execute(
                () -> amendHandler.handle(request.toCommand()));
        log.info("POST /api/trades/amend => {} position entries", entries.size());
        return ApiResponse.ok(entries.stream().map(PositionLedgerEntryDto::from).toList());
    }

    @PostMapping("/cancel")
    public ApiResponse<List<PositionLedgerEntryDto>> cancel(@RequestBody TradeCancelRequest request) {
        log.info("POST /api/trades/cancel tradeId={}", request.tradeId());
        List<PositionLedgerEntry> entries = txExecutor.execute(
                () -> cancelHandler.handle(request.toCommand()));
        log.info("POST /api/trades/cancel => {} position entries", entries.size());
        return ApiResponse.ok(entries.stream().map(PositionLedgerEntryDto::from).toList());
    }
}
