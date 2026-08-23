package com.power.posval.app.dto;

import com.power.posval.domain.command.AuctionResultBatch;
import com.power.posval.domain.command.AuctionResultContract;
import com.power.posval.domain.command.ImportAuctionResults;
import com.power.posval.domain.model.TradeDirection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * REST request DTO for {@code POST /api/da/import}.
 *
 * <p>Carries a parsed {@link AuctionResultBatch} as JSON. The controller converts
 * this DTO into an {@link ImportAuctionResults} command and delegates to
 * {@code AuctionImportService}. Simulator-scope (pv-app). S9.4.
 */
public record DaImportRequest(
    String tenantId,
    String exchange,
    String biddingZone,
    String deliveryDay,
    String exchangeReportedTotalMwh,
    String fileReference,
    List<ContractDto> contracts
) {

    /**
     * Per-contract line item within an {@code AuctionResultBatch}.
     */
    public record ContractDto(
        String contractId,
        String priceMwh,
        String volumeMw,
        String deliveryStart,
        String deliveryEnd,
        String blockType,
        String executionRatio,
        String direction
    ) {}

    /** Convert to the {@link ImportAuctionResults} domain command. */
    public ImportAuctionResults toCommand() {
        List<AuctionResultContract> domainContracts = contracts.stream()
            .map(c -> new AuctionResultContract(
                c.contractId(),
                new BigDecimal(c.priceMwh()),
                new BigDecimal(c.volumeMw()),
                Instant.parse(c.deliveryStart()),
                Instant.parse(c.deliveryEnd()),
                c.blockType(),
                c.executionRatio() != null ? new BigDecimal(c.executionRatio()) : BigDecimal.ONE,
                TradeDirection.valueOf(c.direction())
            ))
            .toList();

        AuctionResultBatch batch = new AuctionResultBatch(
            exchange,
            biddingZone,
            LocalDate.parse(deliveryDay),
            new BigDecimal(exchangeReportedTotalMwh),
            fileReference != null ? fileReference : "",
            domainContracts
        );

        return new ImportAuctionResults(tenantId, batch);
    }
}
