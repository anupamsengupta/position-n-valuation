package com.power.posval.app.controller;

import com.power.posval.app.dto.*;
import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.service.TradeAmendHandler;
import com.power.posval.domain.service.TradeCancelHandler;
import com.power.posval.domain.service.TradeCaptureHandler;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/trades")
public class TradeController {

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
        List<PositionLedgerEntry> entries = txExecutor.execute(
                () -> captureHandler.handle(request.toCommand()));
        return ApiResponse.ok(entries.stream().map(PositionLedgerEntryDto::from).toList());
    }

    @PostMapping("/amend")
    public ApiResponse<List<PositionLedgerEntryDto>> amend(@RequestBody TradeAmendRequest request) {
        List<PositionLedgerEntry> entries = txExecutor.execute(
                () -> amendHandler.handle(request.toCommand()));
        return ApiResponse.ok(entries.stream().map(PositionLedgerEntryDto::from).toList());
    }

    @PostMapping("/cancel")
    public ApiResponse<List<PositionLedgerEntryDto>> cancel(@RequestBody TradeCancelRequest request) {
        List<PositionLedgerEntry> entries = txExecutor.execute(
                () -> cancelHandler.handle(request.toCommand()));
        return ApiResponse.ok(entries.stream().map(PositionLedgerEntryDto::from).toList());
    }
}
