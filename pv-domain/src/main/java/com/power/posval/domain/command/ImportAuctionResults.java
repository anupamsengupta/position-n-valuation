package com.power.posval.domain.command;

import java.util.Objects;

/**
 * Command to import a parsed batch of DA auction execution report contracts.
 * Issued by the AuctionFolderPoller (after parsing) or by the REST endpoint
 * (simulator, pv-app) when a file is uploaded directly.
 * Pattern #17, S4.6, DA-VOL-01.
 */
public record ImportAuctionResults(
    String tenantId,
    AuctionResultBatch batch
) {
    public ImportAuctionResults {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(batch, "batch");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
    }
}
