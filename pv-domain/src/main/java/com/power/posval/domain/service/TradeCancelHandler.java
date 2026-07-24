package com.power.posval.domain.service;

import com.power.posval.domain.command.TradeCancel;
import com.power.posval.domain.model.PositionLedgerEntry;

import java.util.List;

/**
 * Port interface for trade cancellation handling.
 * FR-038: forward unwind closes valid_time; void-ab-initio creates CANCELLED version.
 * Pattern #16, S1.
 */
public interface TradeCancelHandler {

    /** Process trade cancellation → close all current entries. */
    List<PositionLedgerEntry> handle(TradeCancel command);
}
