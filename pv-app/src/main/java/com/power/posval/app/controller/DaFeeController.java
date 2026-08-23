package com.power.posval.app.controller;

import com.power.posval.app.dto.ApiResponse;
import com.power.posval.app.dto.DaFeeBreakdownDto;
import com.power.posval.app.dto.DaFeeRequest;
import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.model.value.ExchangeFeeResult;
import com.power.posval.domain.port.service.DaQueryService;
import com.power.posval.domain.port.service.ExchangeFeeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Simulator REST controller for DA exchange fee computation and retrieval
 * (S9.4, DA-SET-03, DA-UI-05, S16.2.2).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/da/fees} — trigger fee computation (existing)</li>
 *   <li>{@code GET  /api/da/fees} — fee breakdown for a delivery day</li>
 * </ul>
 *
 * <p>Simulator-scope ({@code pv-app} only). D-14.
 */
@RestController
@RequestMapping("/api/da/fees")
public class DaFeeController {

    private static final Logger log = LoggerFactory.getLogger(DaFeeController.class);

    private final ExchangeFeeService exchangeFeeService;
    private final DaQueryService daQueryService;
    private final TransactionalExecutor txExecutor;

    public DaFeeController(ExchangeFeeService exchangeFeeService,
                            DaQueryService daQueryService,
                            TransactionalExecutor txExecutor) {
        this.exchangeFeeService = exchangeFeeService;
        this.daQueryService     = daQueryService;
        this.txExecutor         = txExecutor;
    }

    /**
     * Compute exchange fees for a delivery day (S9.4, DA-SET-03).
     *
     * <p>{@code POST /api/da/fees}
     */
    @PostMapping
    public ApiResponse<ExchangeFeeResult> computeFees(
            @RequestBody DaFeeRequest request) {
        log.info("POST /api/da/fees tenantId={} exchange={} day={}",
            request.tenantId(), request.exchange(), request.deliveryDay());
        ExchangeFeeResult result = txExecutor.execute(
            () -> exchangeFeeService.computeForDay(request.toCommand()));
        log.info("POST /api/da/fees => totalFee={} {}",
            result.totalFeeAmount(), result.currency());
        return ApiResponse.ok(result);
    }

    /**
     * Fee breakdown for a delivery day (S16.2.2, DA-UI-05).
     *
     * <p>{@code GET /api/da/fees?tenantId=&deliveryDay=}
     *
     * <p>Computes fees on-read using the default exchange ({@code EPEX_SPOT}).
     * Returns TRADING and CLEARING line items with the total fee amount.
     * Compute-on-read is safe here because fee computation is deterministic given
     * stable position and schedule data.
     */
    @GetMapping
    public ApiResponse<DaFeeBreakdownDto> getFees(
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestParam String deliveryDay) {

        LocalDate day = LocalDate.parse(deliveryDay);
        log.info("GET /api/da/fees tenantId={} day={}", tenantId, day);

        ExchangeFeeResult result = txExecutor.execute(
            () -> daQueryService.findFeesForDay(tenantId, day));

        List<DaFeeBreakdownDto.LineItemDto> lineItems = result.items().stream()
            .map(item -> new DaFeeBreakdownDto.LineItemDto(
                item.feeType(),
                item.ratePerMwh(),
                item.volumeMwh(),
                item.amount()
            ))
            .toList();

        DaFeeBreakdownDto dto = new DaFeeBreakdownDto(
            result.deliveryDay(),
            result.grossVolumeMwh(),
            lineItems,
            result.totalFeeAmount(),
            result.currency()
        );

        log.info("GET /api/da/fees => {} items totalFee={} {}",
            lineItems.size(), dto.totalFeeAmount(), dto.currency());
        return ApiResponse.ok(dto);
    }
}
