package com.power.posval.domain.command;

import com.power.posval.domain.model.TradeDirection;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * A single executed contract line from an EPEX DA auction execution report.
 * Component of AuctionResultBatch.
 * {@code executionRatio} is in [0, 1] (1.0 = fully executed).
 * {@code blockType} matches BlockType enum names or null for hourly/15-min spot contracts.
 * Pattern #17, S4.6, DA-VOL-01.
 */
public record AuctionResultContract(
    String contractId,
    BigDecimal priceMwh,
    BigDecimal volumeMw,
    Instant deliveryStart,
    Instant deliveryEnd,
    String blockType,       // nullable for non-block (spot) contracts
    BigDecimal executionRatio,
    TradeDirection direction
) {
    public AuctionResultContract {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(priceMwh, "priceMwh");
        Objects.requireNonNull(volumeMw, "volumeMw");
        Objects.requireNonNull(deliveryStart, "deliveryStart");
        Objects.requireNonNull(deliveryEnd, "deliveryEnd");
        Objects.requireNonNull(executionRatio, "executionRatio");
        Objects.requireNonNull(direction, "direction");
        if (contractId.isBlank()) {
            throw new IllegalArgumentException("contractId must not be blank");
        }
        if (!deliveryEnd.isAfter(deliveryStart)) {
            throw new IllegalArgumentException("deliveryEnd must be after deliveryStart");
        }
        if (executionRatio.compareTo(BigDecimal.ZERO) < 0
                || executionRatio.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(
                "executionRatio must be in [0, 1]: " + executionRatio);
        }
    }
}
