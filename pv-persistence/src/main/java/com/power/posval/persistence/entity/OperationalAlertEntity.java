package com.power.posval.persistence.entity;

import com.power.posval.domain.model.AlertCategory;
import com.power.posval.domain.model.AlertSeverity;
import com.power.posval.domain.model.AlertStatus;
import com.power.posval.domain.model.OperationalAlert;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * JPA entity for the {@code operational_alert} table.
 * Persisted operational alert record with an OPEN -> ACKNOWLEDGED -> RESOLVED lifecycle.
 * Pattern #23 (BIGINT sequence, allocationSize=50), S7.1, DA-OPS-01.
 */
@Entity
@Table(name = "operational_alert", schema = "da",
    indexes = {
        @Index(name = "idx_oa_tenant_status_sev_raised",
               columnList = "tenant_id, status, severity, raised_at")
    })
public class OperationalAlertEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "oa_seq")
    @SequenceGenerator(name = "oa_seq",
                       sequenceName = "da.operational_alert_seq",
                       allocationSize = 50)
    private Long id;

    @Column(name = "alert_id", nullable = false, unique = true)
    private UUID alertId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "category", nullable = false, length = 32)
    private String category;

    @Column(name = "severity", nullable = false, length = 16)
    private String severity;

    @Column(name = "alert_type", nullable = false, length = 64)
    private String alertType;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "delivery_day")
    private LocalDate deliveryDay;

    @Column(name = "bidding_zone", length = 16)
    private String biddingZone;

    @Column(name = "source_event_id", length = 128)
    private String sourceEventId;

    @Column(name = "raised_at", nullable = false)
    private Instant raisedAt;

    @Column(name = "acknowledged_by", length = 128)
    private String acknowledgedBy;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    // --- JPA ---

    protected OperationalAlertEntity() {}

    // --- Accessors ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getAlertId() { return alertId; }
    public void setAlertId(UUID alertId) { this.alertId = alertId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getAlertType() { return alertType; }
    public void setAlertType(String alertType) { this.alertType = alertType; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public LocalDate getDeliveryDay() { return deliveryDay; }
    public void setDeliveryDay(LocalDate deliveryDay) { this.deliveryDay = deliveryDay; }

    public String getBiddingZone() { return biddingZone; }
    public void setBiddingZone(String biddingZone) { this.biddingZone = biddingZone; }

    public String getSourceEventId() { return sourceEventId; }
    public void setSourceEventId(String sourceEventId) { this.sourceEventId = sourceEventId; }

    public Instant getRaisedAt() { return raisedAt; }
    public void setRaisedAt(Instant raisedAt) { this.raisedAt = raisedAt; }

    public String getAcknowledgedBy() { return acknowledgedBy; }
    public void setAcknowledgedBy(String acknowledgedBy) { this.acknowledgedBy = acknowledgedBy; }

    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public void setAcknowledgedAt(Instant acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }

    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    // --- Domain conversion ---

    public OperationalAlert toDomain() {
        return OperationalAlert.builder()
            .alertId(this.alertId)
            .tenantId(this.tenantId)
            .category(AlertCategory.valueOf(this.category))
            .severity(AlertSeverity.valueOf(this.severity))
            .alertType(this.alertType)
            .message(this.message)
            .deliveryDay(this.deliveryDay)
            .biddingZone(this.biddingZone)
            .sourceEventId(this.sourceEventId)
            .raisedAt(this.raisedAt)
            .status(AlertStatus.valueOf(this.status))
            .acknowledgedBy(this.acknowledgedBy)
            .acknowledgedAt(this.acknowledgedAt)
            .resolvedAt(this.resolvedAt)
            .build();
    }

    public static OperationalAlertEntity fromDomain(OperationalAlert d) {
        OperationalAlertEntity e = new OperationalAlertEntity();
        e.setAlertId(d.alertId());
        e.setTenantId(d.tenantId());
        e.setCategory(d.category().name());
        e.setSeverity(d.severity().name());
        e.setAlertType(d.alertType());
        e.setMessage(d.message());
        e.setDeliveryDay(d.deliveryDay());
        e.setBiddingZone(d.biddingZone());
        e.setSourceEventId(d.sourceEventId());
        e.setRaisedAt(d.raisedAt());
        e.setStatus(d.status().name());
        e.setAcknowledgedBy(d.acknowledgedBy());
        e.setAcknowledgedAt(d.acknowledgedAt());
        e.setResolvedAt(d.resolvedAt());
        return e;
    }
}
