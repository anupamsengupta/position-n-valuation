package com.power.posval.domain.model;

import com.power.posval.domain.exception.IllegalStateTransitionException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Persisted operational alert record. Created by domain services when an anomaly
 * or failure is detected. Supports an acknowledgement and resolution lifecycle.
 *
 * <p>State machine (Pattern #16):
 * <pre>
 * OPEN -> ACKNOWLEDGED -> RESOLVED
 * OPEN ->               -> RESOLVED
 * </pre>
 *
 * Pattern #2, #16, S4.3, DA-OPS-01.
 */
public final class OperationalAlert {

    private static final java.util.Map<AlertStatus, Set<AlertStatus>> TRANSITIONS =
        java.util.Map.of(
            AlertStatus.OPEN,         Set.of(AlertStatus.ACKNOWLEDGED, AlertStatus.RESOLVED),
            AlertStatus.ACKNOWLEDGED, Set.of(AlertStatus.RESOLVED),
            AlertStatus.RESOLVED,     Set.of()
        );

    private final UUID alertId;
    private final String tenantId;
    private final AlertCategory category;
    private final AlertSeverity severity;
    private final String alertType;
    private final String message;
    private final LocalDate deliveryDay;   // nullable
    private final String biddingZone;      // nullable
    private final String sourceEventId;    // nullable
    private final Instant raisedAt;
    private AlertStatus status;
    private String acknowledgedBy;         // nullable
    private Instant acknowledgedAt;        // nullable
    private Instant resolvedAt;            // nullable

    private OperationalAlert(Builder b) {
        this.alertId        = b.alertId;
        this.tenantId       = b.tenantId;
        this.category       = b.category;
        this.severity       = b.severity;
        this.alertType      = b.alertType;
        this.message        = b.message;
        this.deliveryDay    = b.deliveryDay;
        this.biddingZone    = b.biddingZone;
        this.sourceEventId  = b.sourceEventId;
        this.raisedAt       = b.raisedAt;
        this.status         = b.status;
        this.acknowledgedBy = b.acknowledgedBy;
        this.acknowledgedAt = b.acknowledgedAt;
        this.resolvedAt     = b.resolvedAt;
    }

    public static Builder builder() { return new Builder(); }

    // --- Lifecycle methods ---

    /**
     * Acknowledges this alert. Sets status to ACKNOWLEDGED.
     *
     * @param user the user identifier who acknowledged the alert
     * @param at   the instant of acknowledgement
     * @throws IllegalStateTransitionException if the current status does not permit acknowledgement
     */
    public void acknowledge(String user, Instant at) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(at, "at");
        Set<AlertStatus> permitted = TRANSITIONS.getOrDefault(this.status, Set.of());
        if (!permitted.contains(AlertStatus.ACKNOWLEDGED)) {
            throw new IllegalStateTransitionException(this.status, AlertStatus.ACKNOWLEDGED);
        }
        this.status         = AlertStatus.ACKNOWLEDGED;
        this.acknowledgedBy = user;
        this.acknowledgedAt = at;
    }

    /**
     * Resolves this alert. Sets status to RESOLVED.
     *
     * @param at the instant of resolution
     * @throws IllegalStateTransitionException if the current status does not permit resolution
     */
    public void resolve(Instant at) {
        Objects.requireNonNull(at, "at");
        Set<AlertStatus> permitted = TRANSITIONS.getOrDefault(this.status, Set.of());
        if (!permitted.contains(AlertStatus.RESOLVED)) {
            throw new IllegalStateTransitionException(this.status, AlertStatus.RESOLVED);
        }
        this.status     = AlertStatus.RESOLVED;
        this.resolvedAt = at;
    }

    // --- Accessors ---

    public UUID alertId()           { return alertId; }
    public String tenantId()        { return tenantId; }
    public AlertCategory category() { return category; }
    public AlertSeverity severity() { return severity; }
    public String alertType()       { return alertType; }
    public String message()         { return message; }
    public LocalDate deliveryDay()  { return deliveryDay; }
    public String biddingZone()     { return biddingZone; }
    public String sourceEventId()   { return sourceEventId; }
    public Instant raisedAt()       { return raisedAt; }
    public AlertStatus status()     { return status; }
    public String acknowledgedBy()  { return acknowledgedBy; }
    public Instant acknowledgedAt() { return acknowledgedAt; }
    public Instant resolvedAt()     { return resolvedAt; }

    public static final class Builder {
        private UUID alertId;
        private String tenantId;
        private AlertCategory category;
        private AlertSeverity severity;
        private String alertType;
        private String message;
        private LocalDate deliveryDay;
        private String biddingZone;
        private String sourceEventId;
        private Instant raisedAt;
        private AlertStatus status = AlertStatus.OPEN;
        private String acknowledgedBy;
        private Instant acknowledgedAt;
        private Instant resolvedAt;

        public Builder alertId(UUID v)              { this.alertId = v; return this; }
        public Builder tenantId(String v)           { this.tenantId = v; return this; }
        public Builder category(AlertCategory v)    { this.category = v; return this; }
        public Builder severity(AlertSeverity v)    { this.severity = v; return this; }
        public Builder alertType(String v)          { this.alertType = v; return this; }
        public Builder message(String v)            { this.message = v; return this; }
        public Builder deliveryDay(LocalDate v)     { this.deliveryDay = v; return this; }
        public Builder biddingZone(String v)        { this.biddingZone = v; return this; }
        public Builder sourceEventId(String v)      { this.sourceEventId = v; return this; }
        public Builder raisedAt(Instant v)          { this.raisedAt = v; return this; }
        public Builder status(AlertStatus v)        { this.status = v; return this; }
        public Builder acknowledgedBy(String v)     { this.acknowledgedBy = v; return this; }
        public Builder acknowledgedAt(Instant v)    { this.acknowledgedAt = v; return this; }
        public Builder resolvedAt(Instant v)        { this.resolvedAt = v; return this; }

        public OperationalAlert build() {
            Objects.requireNonNull(alertId,   "alertId");
            Objects.requireNonNull(tenantId,  "tenantId");
            Objects.requireNonNull(category,  "category");
            Objects.requireNonNull(severity,  "severity");
            Objects.requireNonNull(alertType, "alertType");
            Objects.requireNonNull(message,   "message");
            Objects.requireNonNull(raisedAt,  "raisedAt");
            Objects.requireNonNull(status,    "status");
            return new OperationalAlert(this);
        }
    }
}
