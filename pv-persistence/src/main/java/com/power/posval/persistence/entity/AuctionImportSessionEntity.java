package com.power.posval.persistence.entity;

import com.power.posval.domain.model.AuctionImportSession;
import com.power.posval.domain.model.AuctionImportStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA entity for the {@code auction_import_session} table.
 * Tracks one DA auction result import run per (tenant, exchange, biddingZone, deliveryDay).
 * The UNIQUE constraint on that 4-tuple enforces idempotency (DA-VOL-01 Scenario 5).
 * JSONB columns ({@code trade_ids}, {@code validation_errors}) are stored as serialized
 * JSON strings and converted to/from {@code List<String>} via the accessor methods.
 * Pattern #23 (BIGINT sequence, allocationSize=50), S7.1.
 */
@Entity
@Table(name = "auction_import_session", schema = "da",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uq_ais_tenant_exchange_zone_day",
            columnNames = {"tenant_id", "exchange", "bidding_zone", "delivery_day"})
    },
    indexes = {
        @Index(name = "idx_ais_tenant_status",
               columnList = "tenant_id, status")
    })
public class AuctionImportSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ais_seq")
    @SequenceGenerator(name = "ais_seq",
                       sequenceName = "da.auction_import_session_seq",
                       allocationSize = 50)
    private Long id;

    @Column(name = "session_id", nullable = false, unique = true)
    private UUID sessionId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "exchange", nullable = false, length = 32)
    private String exchange;

    @Column(name = "bidding_zone", nullable = false, length = 16)
    private String biddingZone;

    @Column(name = "delivery_day", nullable = false)
    private LocalDate deliveryDay;

    @Column(name = "import_timestamp", nullable = false)
    private Instant importTimestamp;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "exchange_reported_total_mwh", precision = 18, scale = 8)
    private BigDecimal exchangeReportedTotalMwh;

    @Column(name = "imported_total_mwh", precision = 18, scale = 8)
    private BigDecimal importedTotalMwh;

    @Column(name = "interval_count")
    private Integer intervalCount;

    @Column(name = "file_reference", length = 512)
    private String fileReference;

    /**
     * JSONB column holding the list of created trade IDs, serialized as a JSON array string.
     * Adapter converts between {@code List<String>} and JSON string on toDomain/fromDomain.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trade_ids", columnDefinition = "jsonb")
    private String tradeIds;

    /**
     * JSONB column holding validation error messages, serialized as a JSON array string.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_errors", columnDefinition = "jsonb")
    private String validationErrors;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    // --- JPA ---

    protected AuctionImportSessionEntity() {}

    // --- Accessors ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getExchange() { return exchange; }
    public void setExchange(String exchange) { this.exchange = exchange; }

    public String getBiddingZone() { return biddingZone; }
    public void setBiddingZone(String biddingZone) { this.biddingZone = biddingZone; }

    public LocalDate getDeliveryDay() { return deliveryDay; }
    public void setDeliveryDay(LocalDate deliveryDay) { this.deliveryDay = deliveryDay; }

    public Instant getImportTimestamp() { return importTimestamp; }
    public void setImportTimestamp(Instant importTimestamp) { this.importTimestamp = importTimestamp; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public BigDecimal getExchangeReportedTotalMwh() { return exchangeReportedTotalMwh; }
    public void setExchangeReportedTotalMwh(BigDecimal v) { this.exchangeReportedTotalMwh = v; }

    public BigDecimal getImportedTotalMwh() { return importedTotalMwh; }
    public void setImportedTotalMwh(BigDecimal v) { this.importedTotalMwh = v; }

    public Integer getIntervalCount() { return intervalCount; }
    public void setIntervalCount(Integer intervalCount) { this.intervalCount = intervalCount; }

    public String getFileReference() { return fileReference; }
    public void setFileReference(String fileReference) { this.fileReference = fileReference; }

    public String getTradeIds() { return tradeIds; }
    public void setTradeIds(String tradeIds) { this.tradeIds = tradeIds; }

    public String getValidationErrors() { return validationErrors; }
    public void setValidationErrors(String validationErrors) { this.validationErrors = validationErrors; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    // --- Domain conversion ---

    /**
     * Convert to domain model.
     * JSONB fields are deserialized from the compact JSON array format written by
     * {@link #fromDomain(AuctionImportSession)}.
     */
    public AuctionImportSession toDomain() {
        return AuctionImportSession.builder()
            .sessionId(this.sessionId)
            .tenantId(this.tenantId)
            .exchange(this.exchange)
            .biddingZone(this.biddingZone)
            .deliveryDay(this.deliveryDay)
            .importTimestamp(this.importTimestamp)
            .status(AuctionImportStatus.valueOf(this.status))
            .exchangeReportedTotalMwh(this.exchangeReportedTotalMwh)
            .importedTotalMwh(this.importedTotalMwh)
            .intervalCount(this.intervalCount != null ? this.intervalCount : 0)
            .fileReference(this.fileReference)
            .tradeIds(jsonArrayToList(this.tradeIds))
            .validationErrors(jsonArrayToList(this.validationErrors))
            .createdAt(this.createdAt)
            .completedAt(this.completedAt)
            .build();
    }

    /** Factory: create entity from domain model. JSONB fields are serialized as JSON arrays. */
    public static AuctionImportSessionEntity fromDomain(AuctionImportSession d) {
        AuctionImportSessionEntity e = new AuctionImportSessionEntity();
        e.setSessionId(d.sessionId());
        e.setTenantId(d.tenantId());
        e.setExchange(d.exchange());
        e.setBiddingZone(d.biddingZone());
        e.setDeliveryDay(d.deliveryDay());
        e.setImportTimestamp(d.importTimestamp());
        e.setStatus(d.status().name());
        e.setExchangeReportedTotalMwh(d.exchangeReportedTotalMwh());
        e.setImportedTotalMwh(d.importedTotalMwh());
        e.setIntervalCount(d.intervalCount());
        e.setFileReference(d.fileReference());
        e.setTradeIds(listToJsonArray(d.tradeIds()));
        e.setValidationErrors(listToJsonArray(d.validationErrors()));
        e.setCreatedAt(d.createdAt());
        e.setCompletedAt(d.completedAt());
        return e;
    }

    // --- minimal JSON array helpers — no external library dependency ---

    private static List<String> jsonArrayToList(String json) {
        if (json == null || json.isBlank() || json.equals("[]")) {
            return new ArrayList<>();
        }
        // strip [ and ], split on "," boundaries, unescape and trim each element
        String inner = json.trim();
        if (inner.startsWith("[")) inner = inner.substring(1);
        if (inner.endsWith("]")) inner = inner.substring(0, inner.length() - 1);
        inner = inner.trim();
        if (inner.isEmpty()) return new ArrayList<>();

        List<String> result = new ArrayList<>();
        // simple split — values are plain strings without embedded commas or quotes
        for (String token : inner.split(",")) {
            String t = token.trim();
            if (t.startsWith("\"")) t = t.substring(1);
            if (t.endsWith("\"")) t = t.substring(0, t.length() - 1);
            result.add(t);
        }
        return result;
    }

    private static String listToJsonArray(List<String> list) {
        if (list == null || list.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append('"').append(list.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        sb.append("]");
        return sb.toString();
    }
}
