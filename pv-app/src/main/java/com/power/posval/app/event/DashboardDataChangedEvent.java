package com.power.posval.app.event;

import org.springframework.context.ApplicationEvent;

import java.time.Instant;

/**
 * Spring application event published by Kafka listeners after processing
 * domain events that affect dashboard data. Picked up by the SSE controller
 * to push real-time notifications to connected browser clients.
 *
 * <p>Simulator-scope only (pv-app). Not part of the domain event model.
 */
public class DashboardDataChangedEvent extends ApplicationEvent {

    public enum ChangeType {
        SETTLEMENT_COMPUTED,
        ROLLUP_MATERIALIZED,
        MARKET_DATA_REVALUED,
        POSITION_CAPTURED,
        VOLUME_REVALUED
    }

    private final String tenantId;
    private final ChangeType changeType;
    private final String portfolioId; // nullable — null means all portfolios
    private final Instant eventTime;

    public DashboardDataChangedEvent(Object source, String tenantId, ChangeType changeType,
                                      String portfolioId) {
        super(source);
        this.tenantId = tenantId;
        this.changeType = changeType;
        this.portfolioId = portfolioId;
        this.eventTime = Instant.now();
    }

    public String getTenantId() { return tenantId; }
    public ChangeType getChangeType() { return changeType; }
    public String getPortfolioId() { return portfolioId; }
    public Instant getEventTime() { return eventTime; }
}
