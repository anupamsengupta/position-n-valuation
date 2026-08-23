package com.power.posval.app.controller;

import com.power.posval.app.dto.ApiResponse;
import com.power.posval.app.dto.BalancingGroupDto;
import com.power.posval.app.dto.DaNominationComparisonRowDto;
import com.power.posval.app.dto.DaNominationGridDto;
import com.power.posval.app.dto.DaNominationRecordDto;
import com.power.posval.app.dto.DaNominationRequest;
import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.model.BalancingGroup;
import com.power.posval.domain.model.NominationRecord;
import com.power.posval.domain.port.service.DaQueryService;
import com.power.posval.domain.port.service.NominationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * Simulator REST controller for DA nomination recording and comparison grid
 * (S9.4, DA-VOL-03, DA-UI-03, S16.2.2).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/da/nominations} — record per-interval nominations</li>
 *   <li>{@code GET  /api/da/nominations} — nomination comparison grid for a delivery day</li>
 *   <li>{@code GET  /api/da/nominations/balancing-groups} — active BG dropdown list</li>
 * </ul>
 *
 * <p>Simulator-scope ({@code pv-app} only). D-14.
 */
@RestController
@RequestMapping("/api/da/nominations")
public class DaNominationController {

    private static final Logger log = LoggerFactory.getLogger(DaNominationController.class);

    private final NominationService nominationService;
    private final DaQueryService daQueryService;
    private final TransactionalExecutor txExecutor;

    public DaNominationController(NominationService nominationService,
                                   DaQueryService daQueryService,
                                   TransactionalExecutor txExecutor) {
        this.nominationService = nominationService;
        this.daQueryService    = daQueryService;
        this.txExecutor        = txExecutor;
    }

    /**
     * Record per-interval nominations for a balancing group (S9.4, DA-VOL-03).
     *
     * <p>{@code POST /api/da/nominations}
     */
    @PostMapping
    public ApiResponse<List<DaNominationRecordDto>> recordNomination(
            @RequestBody DaNominationRequest request) {
        log.info("POST /api/da/nominations tenantId={} bgId={} day={}",
            request.tenantId(), request.balancingGroupId(), request.deliveryDay());
        List<NominationRecord> records = txExecutor.execute(
            () -> nominationService.recordNomination(request.toCommand()));
        log.info("POST /api/da/nominations => {} records saved", records.size());
        List<DaNominationRecordDto> dtos = records.stream().map(this::toNominationDto).toList();
        return ApiResponse.ok(dtos);
    }

    /**
     * Nomination comparison grid for a delivery day (S16.2.2, DA-UI-03).
     *
     * <p>{@code GET /api/da/nominations?tenantId=&deliveryDay=&zone=&balancingGroupId=}
     *
     * <p>Returns per-interval nominated volumes (latest version per interval) for the
     * delivery day. {@code balancingGroupId} is optional — when omitted, nominations for
     * all active balancing groups are merged and returned ordered by {@code intervalStart}.
     * The {@code zone} parameter is accepted for future filtering but is not used in v1
     * (nominations are keyed to balancing groups, not bidding zones).
     */
    @GetMapping
    public ApiResponse<DaNominationGridDto> getNominations(
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestParam String deliveryDay,
            @RequestParam(required = false) String zone,
            @RequestParam(required = false) String balancingGroupId) {

        LocalDate day = LocalDate.parse(deliveryDay);
        log.info("GET /api/da/nominations tenantId={} day={} bgId={}", tenantId, day, balancingGroupId);

        List<NominationRecord> records = txExecutor.execute(
            () -> daQueryService.findNominationsForDay(tenantId, day, balancingGroupId));

        // Project to comparison rows
        List<DaNominationComparisonRowDto> rows = records.stream()
            .map(r -> new DaNominationComparisonRowDto(
                r.intervalStart(),
                r.intervalEnd(),
                r.balancingGroupId(),
                r.nominatedVolumeMw(),
                r.nominationVersion()
            ))
            .toList();

        // Summary: total nominated MWh = sum(nominatedVolumeMw * intervalHours)
        BigDecimal totalNominatedMwh = records.stream()
            .map(r -> {
                double hours = Duration.between(r.intervalStart(), r.intervalEnd())
                    .toSeconds() / 3600.0;
                return r.nominatedVolumeMw()
                    .multiply(BigDecimal.valueOf(hours));
            })
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        DaNominationGridDto grid = new DaNominationGridDto(
            day, balancingGroupId, rows, totalNominatedMwh, rows.size()
        );

        log.info("GET /api/da/nominations => {} intervals", rows.size());
        return ApiResponse.ok(grid);
    }

    /**
     * Active balancing group dropdown list (S16.2.2, DA-UI-03).
     *
     * <p>{@code GET /api/da/nominations/balancing-groups?tenantId=}
     *
     * <p>Returns all active balancing groups for the tenant, suitable for populating
     * the balancing group filter dropdown in the nominations UI.
     */
    @GetMapping("/balancing-groups")
    public ApiResponse<List<BalancingGroupDto>> getBalancingGroups(
            @RequestParam(defaultValue = "default") String tenantId) {
        log.info("GET /api/da/nominations/balancing-groups tenantId={}", tenantId);
        List<BalancingGroup> groups = txExecutor.execute(
            () -> daQueryService.findActiveBalancingGroups(tenantId));
        List<BalancingGroupDto> dtos = groups.stream()
            .map(bg -> new BalancingGroupDto(bg.bgId(), bg.tsoArea(), bg.bgCode()))
            .toList();
        log.info("GET /api/da/nominations/balancing-groups => {} groups", dtos.size());
        return ApiResponse.ok(dtos);
    }

    private DaNominationRecordDto toNominationDto(NominationRecord r) {
        return new DaNominationRecordDto(
            r.nominationId(),
            r.tenantId(),
            r.balancingGroupId(),
            r.deliveryDay(),
            r.intervalStart(),
            r.intervalEnd(),
            r.nominatedVolumeMw(),
            r.nominationTimestamp(),
            r.nominationVersion(),
            r.submittedBy()
        );
    }
}
