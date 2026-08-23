package com.power.posval.domain.port.service;

import com.power.posval.domain.command.AuctionResultContract;
import com.power.posval.domain.model.BlockDefinition;

import java.time.LocalDate;
import java.util.List;

/**
 * Service port for block order decomposition.
 *
 * <p>Expands a single block-order {@link AuctionResultContract} (e.g. BASELOAD, PEAK,
 * OFF_PEAK, CUSTOM) into the constituent 15-min or hourly intervals that make it up,
 * respecting the block definition's CET time window, applicable-day mask, and holiday
 * calendar exclusions (DA-VOL-02).
 *
 * <p>Individual interval contracts (blockType == null or empty) pass through unchanged as
 * a single-element list.
 *
 * <p>Pattern #18 (Service Port), S5.2.
 */
public interface BlockDecompositionService {

    /**
     * Decompose a block-order contract into its constituent interval contracts.
     *
     * <p>For a PEAK block on a standard business day in DE_LU: returns 48 quarter-hourly
     * intervals covering hours 8–20 CET. On a holiday (per the block definition's
     * {@code holidayCalendarRef}): returns an empty list or falls back to the OFF_PEAK
     * window, depending on the exchange convention.
     *
     * <p>The {@code executionRatio} from the input contract is applied uniformly to all
     * expanded intervals: {@code expandedVolumeMw = contract.volumeMw() * contract.executionRatio()}.
     *
     * @param contract    the block-order contract to decompose; must not be null;
     *                    if {@code blockType()} is null or blank, returned as-is
     * @param blockDef    the effective block definition for the contract's type and zone;
     *                    must not be null if the contract has a blockType
     * @param deliveryDay the CET-interpreted delivery date; used for DST-correct interval
     *                    generation and holiday calendar evaluation
     * @return the expanded list of individual interval contracts; never null;
     *         single-element list for individual (non-block) contracts
     */
    List<AuctionResultContract> decompose(AuctionResultContract contract,
                                           BlockDefinition blockDef,
                                           LocalDate deliveryDay);
}
