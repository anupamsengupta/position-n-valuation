package com.power.posval.domain.service;

import com.power.posval.domain.command.TradeCapture;
import com.power.posval.domain.model.PositionLedgerEntry;

import java.util.List;

/**
 * Port interface for trade capture handling.
 * FR-030: one entry per delivery-month block.
 * FR-034: signed quantity (+long, -short).
 * Pattern #16, S1.
 */
public interface TradeCaptureHandler {

    /** Process initial trade capture → create position ledger entries. */
    List<PositionLedgerEntry> handle(TradeCapture command);
}
