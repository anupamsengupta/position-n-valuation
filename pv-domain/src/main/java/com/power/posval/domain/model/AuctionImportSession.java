package com.power.posval.domain.model;

import com.power.posval.domain.exception.IllegalStateTransitionException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Operational tracking record for a single DA auction result import run.
 * Not bitemporal — one session per (tenantId, exchange, biddingZone, deliveryDay) tuple.
 * The natural business key {@code (tenantId, exchange, biddingZone, deliveryDay)} is used
 * for idempotency checks (DA-VOL-01 Scenario 5).
 *
 * <p>State machine (Pattern #16):
 * <pre>
 * PENDING -> VALIDATING -> VALIDATED -> IMPORTING -> IMPORTED
 *                       -> VALIDATION_FAILED
 *                                     -> IMPORT_FAILED
 * </pre>
 *
 * Pattern #2, #16, S4.3, DA-VOL-01.
 */
public final class AuctionImportSession {

    /** Legal state transitions: key -> set of permitted successor states. */
    private static final java.util.Map<AuctionImportStatus, Set<AuctionImportStatus>> TRANSITIONS =
        java.util.Map.of(
            AuctionImportStatus.PENDING,   Set.of(AuctionImportStatus.VALIDATING),
            AuctionImportStatus.VALIDATING, Set.of(AuctionImportStatus.VALIDATED,
                                                    AuctionImportStatus.VALIDATION_FAILED),
            AuctionImportStatus.VALIDATED,  Set.of(AuctionImportStatus.IMPORTING),
            AuctionImportStatus.IMPORTING,  Set.of(AuctionImportStatus.IMPORTED,
                                                    AuctionImportStatus.IMPORT_FAILED),
            AuctionImportStatus.IMPORTED,           Set.of(),
            AuctionImportStatus.VALIDATION_FAILED,  Set.of(),
            AuctionImportStatus.IMPORT_FAILED,      Set.of()
        );

    private final UUID sessionId;
    private final String tenantId;
    private final String exchange;
    private final String biddingZone;
    private final LocalDate deliveryDay;
    private final Instant importTimestamp;
    private AuctionImportStatus status;
    private final BigDecimal exchangeReportedTotalMwh;
    private BigDecimal importedTotalMwh;
    private final int intervalCount;
    private final String fileReference;
    private final List<String> tradeIds;
    private final List<String> validationErrors;
    private final Instant createdAt;
    private Instant completedAt;

    private AuctionImportSession(Builder b) {
        this.sessionId               = b.sessionId;
        this.tenantId                = b.tenantId;
        this.exchange                = b.exchange;
        this.biddingZone             = b.biddingZone;
        this.deliveryDay             = b.deliveryDay;
        this.importTimestamp         = b.importTimestamp;
        this.status                  = b.status;
        this.exchangeReportedTotalMwh = b.exchangeReportedTotalMwh;
        this.importedTotalMwh        = b.importedTotalMwh;
        this.intervalCount           = b.intervalCount;
        this.fileReference           = b.fileReference;
        this.tradeIds                = new ArrayList<>(b.tradeIds);
        this.validationErrors        = new ArrayList<>(b.validationErrors);
        this.createdAt               = b.createdAt;
        this.completedAt             = b.completedAt;
    }

    public static Builder builder() { return new Builder(); }

    // --- State machine ---

    /**
     * Transitions this session to {@code target}.
     * Validates the transition against the legal state machine.
     *
     * @throws IllegalStateTransitionException if the transition is not permitted
     * @throws IllegalArgumentException        if {@code target} is null
     */
    public void transitionTo(AuctionImportStatus target) {
        Objects.requireNonNull(target, "target");
        Set<AuctionImportStatus> permitted = TRANSITIONS.getOrDefault(this.status, Set.of());
        if (!permitted.contains(target)) {
            throw new IllegalStateTransitionException(this.status, target);
        }
        this.status = target;
    }

    // --- Mutation helpers (called by the import orchestrator) ---

    /** Records a validation error message. */
    public void addValidationError(String message) {
        Objects.requireNonNull(message, "message");
        this.validationErrors.add(message);
    }

    /** Records a trade ID created during import. */
    public void addTradeId(String tradeId) {
        Objects.requireNonNull(tradeId, "tradeId");
        this.tradeIds.add(tradeId);
    }

    /** Sets the computed imported total MWh after validation. */
    public void setImportedTotalMwh(BigDecimal importedTotalMwh) {
        Objects.requireNonNull(importedTotalMwh, "importedTotalMwh");
        this.importedTotalMwh = importedTotalMwh;
    }

    /** Marks the session as completed at the given instant. */
    public void complete(Instant at) {
        Objects.requireNonNull(at, "at");
        this.completedAt = at;
    }

    // --- Accessors ---

    public UUID sessionId()                    { return sessionId; }
    public String tenantId()                   { return tenantId; }
    public String exchange()                   { return exchange; }
    public String biddingZone()                { return biddingZone; }
    public LocalDate deliveryDay()             { return deliveryDay; }
    public Instant importTimestamp()           { return importTimestamp; }
    public AuctionImportStatus status()        { return status; }
    public BigDecimal exchangeReportedTotalMwh() { return exchangeReportedTotalMwh; }
    public BigDecimal importedTotalMwh()       { return importedTotalMwh; }
    public int intervalCount()                 { return intervalCount; }
    public String fileReference()              { return fileReference; }
    public List<String> tradeIds()             { return List.copyOf(tradeIds); }
    public List<String> validationErrors()     { return List.copyOf(validationErrors); }
    public Instant createdAt()                 { return createdAt; }
    public Instant completedAt()               { return completedAt; }

    public static final class Builder {
        private UUID sessionId;
        private String tenantId;
        private String exchange;
        private String biddingZone;
        private LocalDate deliveryDay;
        private Instant importTimestamp;
        private AuctionImportStatus status = AuctionImportStatus.PENDING;
        private BigDecimal exchangeReportedTotalMwh;
        private BigDecimal importedTotalMwh;
        private int intervalCount;
        private String fileReference;
        private List<String> tradeIds = new ArrayList<>();
        private List<String> validationErrors = new ArrayList<>();
        private Instant createdAt;
        private Instant completedAt;

        public Builder sessionId(UUID v)                         { this.sessionId = v; return this; }
        public Builder tenantId(String v)                        { this.tenantId = v; return this; }
        public Builder exchange(String v)                        { this.exchange = v; return this; }
        public Builder biddingZone(String v)                     { this.biddingZone = v; return this; }
        public Builder deliveryDay(LocalDate v)                  { this.deliveryDay = v; return this; }
        public Builder importTimestamp(Instant v)                { this.importTimestamp = v; return this; }
        public Builder status(AuctionImportStatus v)             { this.status = v; return this; }
        public Builder exchangeReportedTotalMwh(BigDecimal v)    { this.exchangeReportedTotalMwh = v; return this; }
        public Builder importedTotalMwh(BigDecimal v)            { this.importedTotalMwh = v; return this; }
        public Builder intervalCount(int v)                      { this.intervalCount = v; return this; }
        public Builder fileReference(String v)                   { this.fileReference = v; return this; }
        public Builder tradeIds(List<String> v)                  { this.tradeIds = new ArrayList<>(v); return this; }
        public Builder validationErrors(List<String> v)          { this.validationErrors = new ArrayList<>(v); return this; }
        public Builder createdAt(Instant v)                      { this.createdAt = v; return this; }
        public Builder completedAt(Instant v)                    { this.completedAt = v; return this; }

        public AuctionImportSession build() {
            Objects.requireNonNull(sessionId,               "sessionId");
            Objects.requireNonNull(tenantId,                "tenantId");
            Objects.requireNonNull(exchange,                "exchange");
            Objects.requireNonNull(biddingZone,             "biddingZone");
            Objects.requireNonNull(deliveryDay,             "deliveryDay");
            Objects.requireNonNull(importTimestamp,         "importTimestamp");
            Objects.requireNonNull(exchangeReportedTotalMwh, "exchangeReportedTotalMwh");
            Objects.requireNonNull(createdAt,               "createdAt");
            return new AuctionImportSession(this);
        }
    }
}
