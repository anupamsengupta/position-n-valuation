package com.power.posval.domain.service;

import com.power.posval.domain.command.TradeAmend;
import com.power.posval.domain.model.PositionLedgerEntry;

import java.util.List;

/**
 * Port interface for trade amendment handling.
 * FR-008: backdated corrections move knowledge time only;
 *         forward-effective changes move valid time.
 * FR-037: amendment carries both processing time and business-effective date.
 * Pattern #16, S1.
 */
public interface TradeAmendHandler {

    /** Process trade amendment → supersede existing entries, create new versions. */
    List<PositionLedgerEntry> handle(TradeAmend command);
}
