# Technical Specification -- Day-Ahead (DA) Exchange Spot v1.0

## S1 -- Metadata & Status

| Field | Value |
|-------|-------|
| Author | solutions-architect |
| Status | DRAFT |
| Version | 1.0 |
| Date | 2026-08-21 |
| Depends On | `day-ahead-exchange-spot.functional.md` v1.0, `functional-spec-position-valuation-v1.0.md`, ADR-001 (Library + Guice), D-1..D-14 |
| Layer | **Library-scope** (pv-domain, pv-persistence, pv-kafka, pv-guice). Batch import REST endpoint is simulator-scope (pv-app). Production scheduling/triggers require the future production hosting layer. |
| Subsystems Touched | **S1** (Position Ledger -- read/write via existing pipeline), **S2** (PriceExpression -- read, ConstantLeaf creation for DA prices), **S3** (VolumeSeries -- write, PROFILE series for DA trades), **S4** (Market Data -- write, DA clearing price fixings + reBAP imbalance prices), **S5a** (Settlement Cells -- write via existing pipeline), **S7** (Rollups -- write via existing pipeline), **S8** (Dependency Index -- write via existing pipeline). New subsystem-adjacent structures: **AuctionImport**, **Nomination**, **ImbalanceSettlement**, **ExchangeFees**, **OperationalAlerts**. |

**Cross-cutting flag:** This feature touches 7 existing subsystems plus introduces 5 new domain areas. The existing subsystems are consumed as-is -- no modifications to S1/S2/S3/S5a/S7/S8 internals are required. The new domain areas are additive. Risk is manageable because the DA trade capture pipeline reuses the existing `TradeCapture` -> `PositionEntryCaptured` -> `SettlementMaterializationJob` flow end-to-end.

---

## S2 -- Scope & Non-Scope

### 2.1 In Scope

1. **Batch auction result import orchestrator** -- accepts parsed EPEX execution reports, validates them, decomposes block orders, and issues one `TradeCapture` command per executed contract/interval (DA-VOL-01, DA-VOL-02).
2. **Block order decomposition engine** -- expands baseload, peak, off-peak, and custom block orders into constituent 15-min or hourly intervals, respecting holiday calendars and DST (DA-VOL-02).
3. **DA clearing price ingestion** -- stores DA auction prices as market data fixings in S4 via `MarketDataRepository`, and creates per-interval `ConstantLeaf` price expressions for trade-level price reference (DA-PRC-01).
4. **Nomination tracking layer** -- stores per-interval nominated volumes per balancing group, detects deviations from traded volumes (DA-VOL-03).
5. **Exchange fee computation** -- configurable, effective-dated fee schedules per exchange and tenant tier; fee computation on gross (absolute) volume (DA-SET-03).
6. **Imbalance settlement** -- ingests TSO reBAP prices, computes per-interval imbalance amounts from nominated-vs-actual deviation, supports monthly aggregation per balancing group (DA-SET-04).
7. **Payment date computation** -- TARGET2 business day calendar, D+2 roll convention (DA-SET-02).
8. **Operational alerts model** -- persisted alert records with category, severity, status, acknowledgement lifecycle (DA-OPS-01).
9. **Reference data entities** -- HolidayCalendar, BlockDefinition, BalancingGroup, AssetToBalancingGroupMapping, TARGET2Calendar, ExchangeFeeSchedule.
10. **EPEX CSV feed parser and folder poller** -- polls a configured directory for new EPEX DA auction result CSV files, parses them into `AuctionResultBatch` domain objects, and delegates to the import orchestrator. Adapter-pattern implementation: a `CsvAuctionResultParser` port in `pv-domain` with a `EpexCsvAuctionResultParser` adapter in `pv-persistence` (file I/O), and a `AuctionFolderPoller` service that watches the configured directory on a schedule.

### 2.2 Defers To

1. **REMIT transaction report generation.** The platform stores all data needed for REMIT Art. 8(1) reporting (UTI, LEI, execution timestamp, venue MIC, price, quantity, delivery period). The report generation and RRM submission are separate subsystem concerns.
2. **Cashflow record generation (PORT-10).** The `CashflowRecord` structure is defined as an outbound event/port interface, not as a full entity within pv-domain. The treasury/liquidity module is a separate deliverable (OQ-6).
3. **Cross-instrument differential P&L (DA-VAL-02).** Requires a trade-linkage mechanism not yet present (OQ-8). Deferred to a follow-on spec.
4. **Configurable reference price for P&L (DA-VAL-01).** The existing `marketPriceExpressionId` on `PositionLedgerEntry` already serves this purpose. DA trades set `marketPriceExpressionId = null` (self-referencing, so P&L = 0 against DA benchmark). The configurable reference price configuration UI and portfolio-level settings are deferred until OQ-7 is resolved.
5. **Email/webhook notifications for alerts (OQ-DA-OPS-2).** v1 is pull-based UI only.
6. **Production scheduling of batch import jobs.** The production hosting layer must supply cron/scheduler infrastructure. The library provides the orchestrator and folder poller; `pv-app` provides a REST trigger and a scheduled poller for simulation.

---

## S3 -- Assumptions & Gaps

| # | Assumption/Gap | Status |
|---|---|---|
| A-1 | MarketCalendar (FR-024/FR-025) implementation exists or will be built concurrently. Block decomposition and DST-correct interval generation depend on it. If not available, a `MarketCalendarPort` interface is defined and a stub adapter is provided in pv-app. | **ASSUMPTION -- needs verification (OQ-3)** |
| A-2 | One deal per 15-min interval (not one deal per auction session with 96 legs). This matches the functional spec's "50-200 deals/day" sizing and the existing `TradeCapture` command's one-entry-per-leg-per-month grain. Each 15-min interval contract is a separate tradeId. | **DECISION — confirmed by stakeholder** |
| A-3 | DA clearing prices are dual-stored: (a) as market data fixings in S4 for benchmark/reference use by other instruments, and (b) as `ConstantLeaf` price expressions referenced by individual trade-legs. This follows OQ-10's observation that "DA price serves as the benchmark." | **DECISION** |
| A-4 | Nomination tracking (DA-VOL-03) is library-scope, housed in pv-domain as a separate bounded context adjacent to position/valuation. FR-021 explicitly states nominations are NOT position origins; they are a parallel volume tracking layer. | **ASSUMPTION -- per OQ-4** |
| A-5 | Imbalance settlement (DA-SET-04) is library-scope. It is a distinct settlement stream from energy settlement, with its own persistence, its own aggregation cycle (monthly vs daily), and its own reference data (reBAP prices, metered actuals). | **ASSUMPTION -- per OQ-5** |
| A-6 | `VolumeReference.multiplier` constraint `(0, 1]` is sufficient for DA where multiplier = 1.0 always. Confirmed by codebase inspection. | **VERIFIED** |
| A-7 | The existing `SettlementCell.valuationType` field (String) supports "REALIZED" as a value for DA energy settlement. If not currently defined, it is a valid extension -- no schema change needed (String field, not enum column). | **ASSUMPTION -- per OQ-13** |
| A-8 | Imbalance price sign convention follows German reBAP rules: positive reBAP = cost to short party. Needs confirmation (OQ-12). The design is sign-agnostic in the formula `(nominated - actual) * imbalancePrice`; the interpretation is config-driven. | **GAP** |

---

## S4 -- Domain Model Additions

### 4.1 Value Objects (Pattern #3, `record` types in `pv-domain/model/value/`)

```
AuctionSessionKey(String exchange, String biddingZone, LocalDate deliveryDay)
BlockSpec(String blockType, LocalTime startTime, LocalTime endTime, Set<DayOfWeek> applicableDays, String holidayCalendarRef)
BalancingGroupCode(String tsoArea, String bgCode)
FeeRate(BigDecimal ratePerMwh, String feeType, String memberTier)
```

### 4.2 Enums (Pattern #4, `pv-domain/model/`)

```
AuctionImportStatus { PENDING, VALIDATING, VALIDATED, IMPORTING, IMPORTED, VALIDATION_FAILED, IMPORT_FAILED }
BlockType { BASELOAD, PEAK, OFF_PEAK, CUSTOM }
AlertCategory { AUCTION_INGESTION, DA_CLEARING_PRICES, NOMINATION_SCHEDULING, IMBALANCE_SETTLEMENT, EXCHANGE_FEES, SETTLEMENT }
AlertSeverity { CRITICAL, WARNING, INFO }
AlertStatus { OPEN, ACKNOWLEDGED, RESOLVED }
CashflowDirection { PAY, RECEIVE }
CashflowType { ENERGY_SETTLEMENT, EXCHANGE_FEE, IMBALANCE_SETTLEMENT }
```

### 4.3 Domain Entities

**AuctionImportSession** -- Operational tracking record (NOT a position entity, NOT bitemporal). Pattern #2 (Aggregate Root).

| Field | Type | Notes |
|---|---|---|
| sessionId | UUID | PK |
| tenantId | String | RLS leading key |
| exchange | String | "EPEX_SPOT" |
| biddingZone | String | "DE_LU", "FR", etc. |
| deliveryDay | LocalDate | CET-interpreted delivery day |
| importTimestamp | Instant | When import was initiated |
| status | AuctionImportStatus | State machine (Pattern #16) |
| exchangeReportedTotalMwh | BigDecimal | From exchange report header |
| importedTotalMwh | BigDecimal | Computed sum after validation |
| intervalCount | int | Expected: 92/96/100 depending on DST |
| fileReference | String | Source file path/URI |
| tradeIds | List of String | Created trade IDs (populated after import) |
| validationErrors | List of String | Populated on failure |
| createdAt | Instant | |
| completedAt | Instant | nullable |

**NominationRecord** -- Per-interval nomination. NOT bitemporal; versioned by `nominationVersion`. Pattern #2.

| Field | Type | Notes |
|---|---|---|
| nominationId | UUID | PK |
| tenantId | String | RLS |
| balancingGroupId | String | FK to BalancingGroup |
| deliveryDay | LocalDate | CET-interpreted |
| intervalStart | Instant | UTC |
| intervalEnd | Instant | UTC |
| nominatedVolumeMw | BigDecimal | scale = PRICE (8) |
| nominationTimestamp | Instant | When submitted to TSO |
| nominationVersion | int | Monotonic per interval+BG |
| submittedBy | String | User/system identifier |

**ImbalanceRecord** -- Per-interval imbalance settlement. NOT bitemporal; versioned by `recordVersion` for TSO corrections. Pattern #1 (Measure -- derived from nominated vs actual).

| Field | Type | Notes |
|---|---|---|
| recordId | UUID | PK |
| tenantId | String | RLS |
| balancingGroupId | String | FK to BalancingGroup |
| intervalStart | Instant | UTC |
| intervalEnd | Instant | UTC |
| nominatedVolumeMw | BigDecimal | Snapshot at computation time |
| actualDeliveredMw | BigDecimal | From TSO metered data |
| imbalanceVolumeMw | BigDecimal | actual - nominated |
| imbalanceEnergyMwh | BigDecimal | imbalanceVolumeMw * intervalHours |
| imbalancePricePerMwh | BigDecimal | reBAP from S4 |
| imbalanceAmount | BigDecimal | MONETARY scale 4 |
| currency | String | "EUR" |
| tsoDataSource | String | Source identifier |
| tsoPublicationTimestamp | Instant | When TSO published the data |
| recordVersion | int | For TSO corrections |
| computedAt | Instant | |
| deliveryDay | LocalDate | CET-interpreted, denormalized for query |

**OperationalAlert** -- Persisted alert record. Pattern #2 (standalone root).

| Field | Type | Notes |
|---|---|---|
| alertId | UUID | PK |
| tenantId | String | RLS |
| category | AlertCategory | Enum |
| severity | AlertSeverity | Enum |
| alertType | String | Free-text sub-type within category |
| message | String | Human-readable |
| deliveryDay | LocalDate | nullable |
| biddingZone | String | nullable |
| sourceEventId | String | Correlation to source event |
| raisedAt | Instant | UTC |
| acknowledgedBy | String | nullable |
| acknowledgedAt | Instant | nullable |
| resolvedAt | Instant | nullable |
| status | AlertStatus | State machine |

### 4.4 Reference Data Entities

**HolidayCalendar** -- per-zone public holidays. Read-only reference data, tenant-independent (system-level).

| Field | Type | Notes |
|---|---|---|
| calendarId | UUID | PK |
| zone | String | "DE", "FR", "AT", etc. |
| holidayDate | LocalDate | |
| holidayName | String | |

**BlockDefinition** -- configurable block types per exchange. Tenant-independent reference data.

| Field | Type | Notes |
|---|---|---|
| blockId | UUID | PK |
| exchange | String | "EPEX_SPOT" |
| blockType | BlockType | Enum |
| biddingZone | String | nullable (null = all zones) |
| startHour | LocalTime | CET |
| endHour | LocalTime | CET |
| applicableDays | String | Encoded day-of-week mask, e.g. "MON-FRI" |
| holidayCalendarRef | String | Reference to HolidayCalendar zone |
| effectiveFrom | LocalDate | |
| effectiveTo | LocalDate | nullable (null = open-ended) |

**BalancingGroup** -- tenant-scoped BRP/BG reference.

| Field | Type | Notes |
|---|---|---|
| bgId | UUID | PK |
| tenantId | String | RLS |
| tsoArea | String | "DE_50HzT", "DE_Amprion", etc. |
| bgCode | String | Official TSO-assigned code |
| activeFrom | LocalDate | |
| activeTo | LocalDate | nullable |

**AssetToBalancingGroupMapping** -- tenant-scoped mapping.

| Field | Type | Notes |
|---|---|---|
| mappingId | UUID | PK |
| tenantId | String | RLS |
| deliveryPointId | String | Or assetId |
| balancingGroupId | UUID | FK to BalancingGroup |
| effectiveFrom | LocalDate | |
| effectiveTo | LocalDate | nullable |

**ExchangeFeeSchedule** -- tenant-scoped, effective-dated.

| Field | Type | Notes |
|---|---|---|
| scheduleId | UUID | PK |
| tenantId | String | RLS |
| exchange | String | "EPEX_SPOT" |
| feeType | String | "TRADING", "CLEARING" |
| ratePerMwh | BigDecimal | PRICE scale 8 |
| memberTier | String | nullable (null = default) |
| effectiveFrom | LocalDate | |
| effectiveTo | LocalDate | nullable |

**TARGET2Calendar** -- system-level banking calendar.

| Field | Type | Notes |
|---|---|---|
| calendarDate | LocalDate | PK |
| isBusinessDay | boolean | |

### 4.5 Domain Events (Pattern #14, `record` types in `pv-domain/event/`)

```
AuctionImportCompleted(String tenantId, UUID sessionId, String biddingZone,
    LocalDate deliveryDay, int tradeCount, Instant eventTime)

AuctionImportFailed(String tenantId, UUID sessionId, String biddingZone,
    LocalDate deliveryDay, List<String> errors, Instant eventTime)

NominationRecorded(String tenantId, String balancingGroupId,
    LocalDate deliveryDay, int intervalCount, Instant eventTime)

NominationDeviationDetected(String tenantId, String balancingGroupId,
    LocalDate deliveryDay, Instant intervalStart,
    BigDecimal tradedMw, BigDecimal nominatedMw, Instant eventTime)

ImbalanceSettlementComputed(String tenantId, String balancingGroupId,
    LocalDate deliveryDay, BigDecimal netImbalanceMwh,
    BigDecimal netImbalanceAmount, Instant eventTime)

ExchangeFeesComputed(String tenantId, String exchange, LocalDate deliveryDay,
    BigDecimal totalFeeAmount, String currency, Instant eventTime)

OperationalAlertRaised(String tenantId, UUID alertId, AlertCategory category,
    AlertSeverity severity, String message, Instant eventTime)
```

### 4.6 Commands (Pattern #17, `record` types in `pv-domain/command/`)

```
ImportAuctionResults(String tenantId, AuctionResultBatch batch)

RecordNomination(String tenantId, String balancingGroupId,
    LocalDate deliveryDay, List<NominationInterval> intervals,
    Instant nominationTimestamp, String submittedBy)

ComputeImbalanceSettlement(String tenantId, String balancingGroupId,
    LocalDate deliveryDay)

ComputeExchangeFees(String tenantId, String exchange, LocalDate deliveryDay)
```

**AuctionResultBatch** (input DTO, `pv-domain/command/`):

```
AuctionResultBatch(String exchange, String biddingZone, LocalDate deliveryDay,
    BigDecimal exchangeReportedTotalMwh, String fileReference,
    List<AuctionResultContract> contracts)

AuctionResultContract(String contractId, BigDecimal priceMwh, BigDecimal volumeMw,
    Instant deliveryStart, Instant deliveryEnd, String blockType,
    BigDecimal executionRatio, TradeDirection direction)
```

---

## S5 -- New / Modified Ports

### 5.1 New Port Interfaces (Pattern #18, `pv-domain/port/`)

**AuctionImportSessionRepository** (`pv-domain/port/repository/`)

```
save(AuctionImportSession session)
findBySessionId(String tenantId, UUID sessionId) -> Optional<AuctionImportSession>
findByDeliveryDay(String tenantId, String exchange, String biddingZone, LocalDate deliveryDay) -> Optional<AuctionImportSession>
updateStatus(String tenantId, UUID sessionId, AuctionImportStatus status, Instant completedAt)
```

The `findByDeliveryDay` method provides the idempotency check for re-imports (DA-VOL-01 Scenario 5).

**NominationRepository** (`pv-domain/port/repository/`)

```
save(NominationRecord record)
saveAll(List<NominationRecord> records)
findByDeliveryDay(String tenantId, String balancingGroupId, LocalDate deliveryDay) -> List<NominationRecord>
findLatestByInterval(String tenantId, String balancingGroupId, Instant intervalStart) -> Optional<NominationRecord>
```

**ImbalanceRecordRepository** (`pv-domain/port/repository/`)

```
save(ImbalanceRecord record)
saveAll(List<ImbalanceRecord> records)
findByDeliveryDay(String tenantId, String balancingGroupId, LocalDate deliveryDay) -> List<ImbalanceRecord>
findByMonth(String tenantId, String balancingGroupId, YearMonth month) -> List<ImbalanceRecord>
```

**OperationalAlertRepository** (`pv-domain/port/repository/`)

```
save(OperationalAlert alert)
findOpen(String tenantId) -> List<OperationalAlert>
findByCategory(String tenantId, AlertCategory category, AlertStatus status) -> List<OperationalAlert>
findByDeliveryDay(String tenantId, LocalDate deliveryDay) -> List<OperationalAlert>
acknowledge(String tenantId, UUID alertId, String acknowledgedBy, Instant acknowledgedAt)
resolve(String tenantId, UUID alertId, Instant resolvedAt)
countByStatus(String tenantId, AlertStatus status) -> Map<AlertCategory, Long>
```

**ExchangeFeeScheduleRepository** (`pv-domain/port/repository/`)

```
findEffective(String tenantId, String exchange, String feeType, LocalDate deliveryDate) -> Optional<ExchangeFeeSchedule>
findEffective(String tenantId, String exchange, String feeType, String memberTier, LocalDate deliveryDate) -> Optional<ExchangeFeeSchedule>
save(ExchangeFeeSchedule schedule)
```

**HolidayCalendarRepository** (`pv-domain/port/repository/`)

```
isHoliday(String zone, LocalDate date) -> boolean
findByZoneAndYear(String zone, int year) -> List<HolidayCalendar>
```

Note: HolidayCalendar is system-level (not tenant-scoped). This is intentional -- public holidays are facts, not tenant configuration.

**BlockDefinitionRepository** (`pv-domain/port/repository/`)

```
findEffective(String exchange, BlockType blockType, String biddingZone, LocalDate deliveryDate) -> Optional<BlockDefinition>
findAllEffective(String exchange, LocalDate deliveryDate) -> List<BlockDefinition>
```

Note: BlockDefinitions are system-level reference data, not tenant-scoped.

**BalancingGroupRepository** (`pv-domain/port/repository/`)

```
findById(String tenantId, UUID bgId) -> Optional<BalancingGroup>
findByCode(String tenantId, String bgCode) -> Optional<BalancingGroup>
findActive(String tenantId) -> List<BalancingGroup>
```

**TARGET2CalendarRepository** (`pv-domain/port/repository/`)

```
isBusinessDay(LocalDate date) -> boolean
nextBusinessDay(LocalDate date) -> LocalDate
nthBusinessDayAfter(LocalDate date, int n) -> LocalDate
```

System-level, not tenant-scoped.

**MarketCalendarPort** (`pv-domain/port/` -- if not already existing)

```
intervalsForDay(String biddingZone, LocalDate deliveryDay, TimeGranularity granularity) -> List<DeliveryPeriod>
intervalCount(String biddingZone, LocalDate deliveryDay, TimeGranularity granularity) -> int
isPeakInterval(String biddingZone, DeliveryPeriod interval) -> boolean
deliveryDayBoundaries(String biddingZone, LocalDate deliveryDay) -> DeliveryPeriod
```

This port encapsulates DST-correct interval generation. If MarketCalendar already exists (FR-024), this port wraps it. If not, it must be built. See A-1.

**AuctionResultParser** (`pv-domain/port/`)

```
parse(Path csvFile) -> AuctionResultBatch
supportedFormat() -> String
```

Port interface for parsing exchange feed files into the `AuctionResultBatch` domain object. The parser is format-specific — v1 supports EPEX DA CSV only. Future formats (XML, API response) implement the same port.

**AuctionFolderPollerConfig** (`pv-domain/model/value/`)

```
record AuctionFolderPollerConfig(
    Path inboxDirectory,           // folder to watch for new CSV files
    Path processedDirectory,       // folder to move successfully processed files to
    Path failedDirectory,          // folder to move failed files to
    Duration pollInterval,         // how often to check (default: 30s)
    String filePattern,            // glob pattern (default: "*.csv")
    String defaultTenantId         // tenant context for the import (simulator: "default")
)
```

### 5.2 New Service Ports (Pattern #18, `pv-domain/port/service/`)

**AuctionImportService** (`pv-domain/port/service/`)

```
importAuctionResults(ImportAuctionResults command) -> AuctionImportSession
importFromFile(String tenantId, Path csvFile) -> AuctionImportSession
getImportSession(String tenantId, UUID sessionId) -> Optional<AuctionImportSession>
getImportHistory(String tenantId, String exchange, String biddingZone) -> List<AuctionImportSession>
```

`importFromFile` is the convenience method that delegates to the `AuctionResultParser` port to parse the CSV, then calls `importAuctionResults` with the resulting batch.

**AuctionFolderPoller** (`pv-domain/port/service/`)

```
pollOnce() -> List<AuctionImportSession>
start()
stop()
isRunning() -> boolean
```

Scheduled service that:
1. Scans `inboxDirectory` for files matching `filePattern`
2. For each file: parses via `AuctionResultParser`, imports via `AuctionImportService`
3. On success: moves file to `processedDirectory`
4. On failure: moves file to `failedDirectory`, raises `OperationalAlert(CRITICAL, AUCTION_INGESTION)`
5. File naming convention: `{exchange}_{zone}_{deliveryDay_YYYYMMDD}.csv` — extracts `exchange`, `biddingZone`, and `deliveryDay` from the filename
6. Already-processed files (matching an IMPORTED session) are moved to `processedDirectory` without re-import (idempotent)

**NominationService** (`pv-domain/port/service/`)

```
recordNomination(RecordNomination command) -> List<NominationRecord>
compareWithTraded(String tenantId, String balancingGroupId, LocalDate deliveryDay) -> List<NominationDeviation>
```

`NominationDeviation` is a value object: `record NominationDeviation(Instant intervalStart, BigDecimal tradedMw, BigDecimal nominatedMw, BigDecimal deviationMw)`.

**ImbalanceSettlementService** (`pv-domain/port/service/`)

```
computeForDay(ComputeImbalanceSettlement command) -> List<ImbalanceRecord>
monthlyAggregate(String tenantId, String balancingGroupId, YearMonth month) -> ImbalanceMonthSummary
```

`ImbalanceMonthSummary` is a value object: `record ImbalanceMonthSummary(YearMonth month, String balancingGroupId, BigDecimal netImbalanceMwh, BigDecimal netImbalanceAmount, String currency, List<ImbalanceDaySummary> dailyBreakdown)`.

**ExchangeFeeService** (`pv-domain/port/service/`)

```
computeForDay(ComputeExchangeFees command) -> ExchangeFeeResult
```

`ExchangeFeeResult` is a value object: `record ExchangeFeeResult(LocalDate deliveryDay, BigDecimal grossVolumeMwh, List<FeeLineItem> items, BigDecimal totalFeeAmount, String currency)`.

**OperationalAlertService** (`pv-domain/port/service/`)

```
raise(OperationalAlert alert)
acknowledgeAlert(String tenantId, UUID alertId, String user)
resolveAlert(String tenantId, UUID alertId)
getOpenAlerts(String tenantId) -> List<OperationalAlert>
getAlertCounts(String tenantId) -> Map<AlertCategory, Long>
```

**PaymentDateService** (`pv-domain/port/service/`)

```
computePaymentDate(LocalDate deliveryDay, int businessDaysAfter) -> LocalDate
```

Default: `businessDaysAfter = 2` for DA ECC settlement.

### 5.3 Modified Ports -- NONE

No existing port interfaces require modification. The DA pipeline reuses `TradeCaptureHandler`, `PositionLedgerRepository`, `SettlementCellRepository`, `PriceExpressionRepository`, `MarketDataPort`, `DomainEventPublisher`, `DependencyIndex`, `RollupRepository`, and `TradeLegRollupRepository` as-is.

---

## S6 -- Adapters

### 6.1 JPA Adapters (`pv-persistence/adapter/`)

Each new repository port gets a `Jpa*` implementation. Pattern #18 (Port + Adapter).

| Port | Adapter | Key implementation notes |
|---|---|---|
| AuctionImportSessionRepository | JpaAuctionImportSessionRepository | Standard CRUD + idempotency query by (tenantId, exchange, biddingZone, deliveryDay). Uses `@SequenceGenerator` with `allocationSize=50` (Pattern #23). |
| NominationRepository | JpaNominationRepository | Query by (tenantId, balancingGroupId, deliveryDay) with order by intervalStart. |
| ImbalanceRecordRepository | JpaImbalanceRecordRepository | Monthly aggregation query uses JPQL `SUM()` over (tenantId, balancingGroupId, month range). |
| OperationalAlertRepository | JpaOperationalAlertRepository | Filtered queries by status, category, deliveryDay. Supports count aggregation. |
| ExchangeFeeScheduleRepository | JpaExchangeFeeScheduleRepository | Effective-date lookup: `effectiveFrom <= :date AND (effectiveTo IS NULL OR effectiveTo > :date)`. |
| HolidayCalendarRepository | JpaHolidayCalendarRepository | Simple date lookup. No tenant_id (system-level). |
| BlockDefinitionRepository | JpaBlockDefinitionRepository | No tenant_id (system-level). |
| BalancingGroupRepository | JpaBalancingGroupRepository | Tenant-scoped. |
| TARGET2CalendarRepository | JpaTargetCalendarRepository | System-level. `nextBusinessDay` iterates forward from date until `isBusinessDay = true`. |
| MarketCalendarPort | StubMarketCalendarAdapter (pv-app) | Generates DST-correct intervals using `ZoneId("Europe/Berlin")` and `java.time`. Production adapter TBD (A-1). |

### 6.2 CSV Parser Adapter (`pv-persistence/adapter/`)

**EpexCsvAuctionResultParser** implements `AuctionResultParser`

Parses EPEX DA auction result CSV files. Pattern #18 (Port + Adapter).

**Expected CSV format** (EPEX DA execution report):

```
# EPEX_SPOT DA Auction Results
# Delivery Day: 2026-09-16
# Bidding Zone: DE_LU
# Total MWh: 12450.00
ContractId,DeliveryStart,DeliveryEnd,Price,Volume,Direction,BlockType,ExecutionRatio
DELU-20260916-0000,2026-09-15T22:00:00Z,2026-09-15T22:15:00Z,45.20,30.0,BUY,,1.0
DELU-20260916-0015,2026-09-15T22:15:00Z,2026-09-15T22:30:00Z,44.80,30.0,BUY,,1.0
...
```

**Parsing rules:**
1. Lines starting with `#` are header metadata. Extract `Delivery Day`, `Bidding Zone`, `Total MWh` from header comments.
2. First non-comment, non-empty line is the column header row.
3. Subsequent lines are contract rows.
4. `DeliveryStart`/`DeliveryEnd` are ISO-8601 UTC instants.
5. `Price` is EUR/MWh, decimal (may be negative).
6. `Volume` is MW, decimal (always positive — direction indicates BUY/SELL).
7. `Direction` is `BUY` or `SELL`.
8. `BlockType` is empty for individual interval contracts; `BASELOAD`, `PEAK`, `OFF_PEAK`, or `CUSTOM` for block orders.
9. `ExecutionRatio` is `1.0` for fully executed; `0.0-1.0` for partially executed blocks.
10. Encoding: UTF-8. Line separator: `\n` or `\r\n`. Decimal separator: `.` (dot).

**Validation during parse (structural, not business):**
- All required columns present
- All rows parseable (numeric fields, ISO timestamps, valid direction)
- No duplicate ContractId values within one file
- On parse error: throw `CsvParseException` with line number and error detail

**File naming convention:** `{exchange}_{zone}_{YYYYMMDD}.csv` (e.g., `EPEX_SPOT_DE_LU_20260916.csv`). The parser extracts `exchange` and `biddingZone` from the filename as a cross-check against the header metadata.

### 6.3 Folder Poller (`pv-domain/service/`)

**DefaultAuctionFolderPoller** implements `AuctionFolderPoller`

Library-scope service. Uses `java.nio.file.Files.list()` — no framework dependency. Scheduling is the host's responsibility:

- **pv-app (simulator):** Spring `@Scheduled(fixedDelayString = "${pv.da.poller.interval:30000}")` calls `pollOnce()`.
- **Production host (future):** cron job, ECS scheduled task, or framework scheduler calls `pollOnce()`.

**pollOnce() flow:**

```
1. List files in inboxDirectory matching filePattern
2. Sort by filename (deterministic processing order)
3. For each file:
   a. Extract tenantId from config (simulator: hardcoded "default"; production: from filename convention or subfolder)
   b. Parse CSV via AuctionResultParser.parse(file)
   c. Call AuctionImportService.importAuctionResults(batch)
   d. If session.status == IMPORTED:
      - Move file to processedDirectory/{YYYY-MM-DD}/
   e. If session returned from idempotency check (already imported):
      - Move file to processedDirectory/{YYYY-MM-DD}/ (no-op, no alert)
   f. On CsvParseException or validation failure:
      - Move file to failedDirectory/{YYYY-MM-DD}/
      - Raise OperationalAlert(CRITICAL, AUCTION_INGESTION, "CSV parse failed: " + error)
      - Log error with file path and line number
4. Return list of all AuctionImportSession results
```

**Directory structure:**

```
{configured-root}/
  inbox/              <- drop CSV files here
  processed/
    2026-09-15/       <- successfully imported files, organized by date
    2026-09-16/
  failed/
    2026-09-15/       <- files that failed parsing or validation
```

**Configuration** (pv-app `application.yml`):

```yaml
pv:
  da:
    poller:
      enabled: true
      inbox-directory: ./da-import/inbox
      processed-directory: ./da-import/processed
      failed-directory: ./da-import/failed
      interval: 30000  # ms
      file-pattern: "*.csv"
```

### 6.4 Kafka Adapters -- See S8 Event Flow

No new Kafka adapters required for v1. All events flow through the existing outbox relay pattern (#24). New consumers may be added in future phases (e.g., `ImbalanceDataReceivedConsumer` for automatic TSO data ingestion).

---

## S7 -- Data Model Impact

### 7.1 New Tables

All tables include `tenant_id` as the leading column (unless marked system-level). All IDs use `BIGINT` sequences with `allocationSize=50` (Pattern #23) plus a `UUID` business key where referenced externally.

**auction_import_session**

| Column | Type | Constraints |
|---|---|---|
| id | BIGINT | PK, sequence |
| session_id | UUID | UNIQUE per tenant |
| tenant_id | VARCHAR(64) | NOT NULL, RLS |
| exchange | VARCHAR(32) | NOT NULL |
| bidding_zone | VARCHAR(16) | NOT NULL |
| delivery_day | DATE | NOT NULL |
| import_timestamp | TIMESTAMPTZ | NOT NULL |
| status | VARCHAR(32) | NOT NULL |
| exchange_reported_total_mwh | NUMERIC(18,8) | |
| imported_total_mwh | NUMERIC(18,8) | |
| interval_count | INT | |
| file_reference | VARCHAR(512) | |
| trade_ids | JSONB | |
| validation_errors | JSONB | |
| created_at | TIMESTAMPTZ | NOT NULL |
| completed_at | TIMESTAMPTZ | |

Index: `UNIQUE(tenant_id, exchange, bidding_zone, delivery_day)` -- idempotency key.

**nomination_record**

| Column | Type | Constraints |
|---|---|---|
| id | BIGINT | PK, sequence |
| nomination_id | UUID | UNIQUE per tenant |
| tenant_id | VARCHAR(64) | NOT NULL, RLS |
| balancing_group_id | VARCHAR(64) | NOT NULL |
| delivery_day | DATE | NOT NULL |
| interval_start | TIMESTAMPTZ | NOT NULL |
| interval_end | TIMESTAMPTZ | NOT NULL |
| nominated_volume_mw | NUMERIC(18,8) | NOT NULL |
| nomination_timestamp | TIMESTAMPTZ | NOT NULL |
| nomination_version | INT | NOT NULL DEFAULT 1 |
| submitted_by | VARCHAR(128) | |

Index: `(tenant_id, balancing_group_id, delivery_day, interval_start)`.

**imbalance_record**

| Column | Type | Constraints |
|---|---|---|
| id | BIGINT | PK, sequence |
| record_id | UUID | UNIQUE per tenant |
| tenant_id | VARCHAR(64) | NOT NULL, RLS |
| balancing_group_id | VARCHAR(64) | NOT NULL |
| delivery_day | DATE | NOT NULL |
| interval_start | TIMESTAMPTZ | NOT NULL |
| interval_end | TIMESTAMPTZ | NOT NULL |
| nominated_volume_mw | NUMERIC(18,8) | |
| actual_delivered_mw | NUMERIC(18,8) | |
| imbalance_volume_mw | NUMERIC(18,8) | |
| imbalance_energy_mwh | NUMERIC(18,8) | |
| imbalance_price_mwh | NUMERIC(18,8) | |
| imbalance_amount | NUMERIC(18,4) | MONETARY |
| currency | VARCHAR(3) | NOT NULL DEFAULT 'EUR' |
| tso_data_source | VARCHAR(128) | |
| tso_publication_timestamp | TIMESTAMPTZ | |
| record_version | INT | NOT NULL DEFAULT 1 |
| computed_at | TIMESTAMPTZ | NOT NULL |

Index: `(tenant_id, balancing_group_id, delivery_day)`, `(tenant_id, balancing_group_id, interval_start)`.

**operational_alert**

| Column | Type | Constraints |
|---|---|---|
| id | BIGINT | PK, sequence |
| alert_id | UUID | UNIQUE per tenant |
| tenant_id | VARCHAR(64) | NOT NULL, RLS |
| category | VARCHAR(32) | NOT NULL |
| severity | VARCHAR(16) | NOT NULL |
| alert_type | VARCHAR(64) | NOT NULL |
| message | TEXT | NOT NULL |
| delivery_day | DATE | |
| bidding_zone | VARCHAR(16) | |
| source_event_id | VARCHAR(128) | |
| raised_at | TIMESTAMPTZ | NOT NULL |
| acknowledged_by | VARCHAR(128) | |
| acknowledged_at | TIMESTAMPTZ | |
| resolved_at | TIMESTAMPTZ | |
| status | VARCHAR(16) | NOT NULL DEFAULT 'OPEN' |

Index: `(tenant_id, status, severity, raised_at DESC)` -- dashboard query.

**exchange_fee_schedule**

| Column | Type | Constraints |
|---|---|---|
| id | BIGINT | PK, sequence |
| schedule_id | UUID | UNIQUE per tenant |
| tenant_id | VARCHAR(64) | NOT NULL, RLS |
| exchange | VARCHAR(32) | NOT NULL |
| fee_type | VARCHAR(16) | NOT NULL |
| rate_per_mwh | NUMERIC(18,8) | NOT NULL |
| member_tier | VARCHAR(32) | |
| effective_from | DATE | NOT NULL |
| effective_to | DATE | |

Index: `(tenant_id, exchange, fee_type, effective_from)`.

**holiday_calendar** (system-level, no tenant_id)

| Column | Type | Constraints |
|---|---|---|
| id | BIGINT | PK, sequence |
| zone | VARCHAR(8) | NOT NULL |
| holiday_date | DATE | NOT NULL |
| holiday_name | VARCHAR(128) | NOT NULL |

Index: `UNIQUE(zone, holiday_date)`.

**block_definition** (system-level)

| Column | Type | Constraints |
|---|---|---|
| id | BIGINT | PK, sequence |
| block_id | UUID | UNIQUE |
| exchange | VARCHAR(32) | NOT NULL |
| block_type | VARCHAR(16) | NOT NULL |
| bidding_zone | VARCHAR(16) | |
| start_hour | TIME | NOT NULL |
| end_hour | TIME | NOT NULL |
| applicable_days | VARCHAR(32) | NOT NULL |
| holiday_calendar_ref | VARCHAR(8) | |
| effective_from | DATE | NOT NULL |
| effective_to | DATE | |

**balancing_group**

| Column | Type | Constraints |
|---|---|---|
| id | BIGINT | PK, sequence |
| bg_id | UUID | UNIQUE per tenant |
| tenant_id | VARCHAR(64) | NOT NULL, RLS |
| tso_area | VARCHAR(32) | NOT NULL |
| bg_code | VARCHAR(64) | NOT NULL |
| active_from | DATE | NOT NULL |
| active_to | DATE | |

Index: `UNIQUE(tenant_id, bg_code)`.

**asset_to_bg_mapping**

| Column | Type | Constraints |
|---|---|---|
| id | BIGINT | PK, sequence |
| mapping_id | UUID | UNIQUE per tenant |
| tenant_id | VARCHAR(64) | NOT NULL, RLS |
| delivery_point_id | VARCHAR(64) | NOT NULL |
| balancing_group_id | UUID | NOT NULL |
| effective_from | DATE | NOT NULL |
| effective_to | DATE | |

Index: `(tenant_id, delivery_point_id, effective_from)`.

**target2_calendar** (system-level)

| Column | Type | Constraints |
|---|---|---|
| calendar_date | DATE | PK |
| is_business_day | BOOLEAN | NOT NULL |

### 7.2 Existing Tables -- No Modifications

`position_ledger_entry`, `settlement_cell`, `volume_series`, `volume_interval`, `price_expression`, `market_data_fixing`, `rollup_cell`, `trade_leg_rollup`, `dependency_edge`, `outbox` -- all used as-is. No schema changes.

---

## S8 -- Event Flow

### 8.1 Batch Import Pipeline (write path)

```
[External: EPEX CSV file dropped in inbox/]
    |
    v
AuctionFolderPoller.pollOnce()
    |
    +-- Scan inbox/ for *.csv files
    +-- For each file: EpexCsvAuctionResultParser.parse(file) -> AuctionResultBatch
    +-- Construct ImportAuctionResults command from batch
    |
    v
AuctionImportOrchestrator.importAuctionResults(ImportAuctionResults cmd)
    |
    +-- 1. Idempotency check: AuctionImportSessionRepo.findByDeliveryDay()
    |       If IMPORTED session exists for same (exchange, zone, day) -> return existing session
    |
    +-- 2. Create AuctionImportSession(status=PENDING)
    |
    +-- 3. Validate batch:
    |       a. Count intervals == MarketCalendarPort.intervalCount(zone, day, granularity)
    |       b. Sum(contract.volumeMw * intervalHours) == exchangeReportedTotalMwh
    |       c. Price plausibility: -500 <= price <= 4000 (raises WARNING alert, does NOT reject)
    |       d. On validation failure: session.status = VALIDATION_FAILED,
    |          raise OperationalAlert(CRITICAL, AUCTION_INGESTION),
    |          publish AuctionImportFailed, RETURN
    |
    +-- 4. Block order decomposition (DA-VOL-02):
    |       For each contract with blockType != null:
    |           BlockDecompositionService.decompose(contract, blockDef, holidayCalendar, deliveryDay)
    |           -> List<AuctionResultContract> expanded intervals
    |
    +-- 5. DA clearing price ingestion (DA-PRC-01):
    |       For each interval:
    |           a. Store as market data fixing: MarketDataRepository.saveFixing(
    |              series="EPEX_DA_" + zone, intervalStart, price)
    |           b. Create ConstantLeaf price expression -> PriceExpressionRepository.save()
    |              Return the priceExpressionId
    |
    +-- 6. Trade capture (per interval contract):
    |       For each contract (or expanded interval from block decomposition):
    |           TradeCapture cmd = new TradeCapture(
    |               tradeId = auctionSession.exchange + "/" + zone + "/" + deliveryDay + "/" + contractId,
    |               tradeVersion = 1,
    |               tradeLegId = tradeId + "/LEG1",
    |               tenantId = cmd.tenantId,
    |               deliveryPeriod = contract.deliveryStart..deliveryEnd,
    |               quantity = contract.volumeMw * executionRatio,
    |               direction = contract.direction,
    |               volumeUnit = MW,
    |               priceExpressionId = (from step 5b),
    |               marketPriceExpressionId = null,  // DA self-references
    |               portfolioId = (tenant config),
    |               deliveryPointId = zone,
    |               originType = "EXCHANGE_FILL",
    |               businessEffectiveDate = auction execution timestamp,
    |               assetId = null,
    |               multiplier = 1.0,
    |               volumeSeriesKey = (generated PROFILE key),
    |               meteredSeriesKey = null
    |           );
    |           TradeCaptureHandler.handle(cmd)
    |           -> Existing pipeline: ledger entry + PositionEntryCaptured event
    |
    +-- 7. Session complete:
    |       session.status = IMPORTED
    |       session.importedTotalMwh = computed sum
    |       session.tradeIds = list of created trade IDs
    |       Publish AuctionImportCompleted via outbox (Pattern #24)
    |
    +-- 8. Alerts:
            For negative prices: raise OperationalAlert(INFO, DA_CLEARING_PRICES)
            For extreme prices: raise OperationalAlert(WARNING, DA_CLEARING_PRICES)
```

### 8.2 Downstream Pipeline (existing, unchanged)

```
PositionEntryCaptured (per trade-leg)
    |
    v
TradeCapturedConsumer (pv-kafka, Pattern #26)
    |
    +-- SettlementMaterializationJob.execute()
    |       -> SettlementCell(s) persisted (S5a)
    |       -> DependencyEdge(s) upserted (S8)
    |       -> SettlementComputed event published via outbox
    |
    +-- TradeIntervalCacheRebuilder.rebuildForTradeLeg() (S6b)
```

```
SettlementComputed
    |
    v
SettlementPublishedConsumer (pv-kafka)
    |
    +-- RollupMaterializationService (S7)
```

This is the existing pipeline. DA trades flow through it identically to any other instrument. No changes needed.

### 8.3 Nomination and Imbalance Flow (new)

```
RecordNomination command
    |
    v
DefaultNominationService.recordNomination()
    |
    +-- Save NominationRecord(s)
    |
    +-- Compare with traded volumes (PositionLedgerRepository.findAllByDeliveryRange)
    |       If deviation detected: publish NominationDeviationDetected via outbox
    |       Raise OperationalAlert(WARNING, NOMINATION_SCHEDULING)
    |
    +-- Publish NominationRecorded via outbox
```

```
ComputeImbalanceSettlement command
    |
    v
DefaultImbalanceSettlementService.computeForDay()
    |
    +-- Load nominations: NominationRepository.findByDeliveryDay()
    +-- Load actuals: MeteredActualRepository (existing S3 port) or via MarketDataPort
    +-- Load reBAP prices: MarketDataPort.lookupFixing("REBAP_" + tsoArea, intervalStart)
    +-- For each interval:
    |       imbalanceVolume = actual - nominated
    |       imbalanceEnergy = imbalanceVolume * intervalHours
    |       imbalanceAmount = imbalanceEnergy * reBAP (MONETARY scale 4)
    |       -> ImbalanceRecord
    |
    +-- Save all ImbalanceRecords
    +-- Publish ImbalanceSettlementComputed via outbox
```

### 8.4 Exchange Fee Flow (new)

```
ComputeExchangeFees command
    |
    v
DefaultExchangeFeeService.computeForDay()
    |
    +-- Load positions for delivery day: PositionLedgerRepository.findAllByDeliveryRange()
    +-- Compute gross volume: sum(abs(quantity) * intervalHours) across all intervals
    +-- Load effective fee schedule: ExchangeFeeScheduleRepository.findEffective()
    +-- For each fee type (TRADING, CLEARING):
    |       feeAmount = grossVolumeMwh * ratePerMwh (MONETARY scale 4)
    |       -> FeeLineItem
    |
    +-- Publish ExchangeFeesComputed via outbox
```

### 8.5 Topic Naming

Per existing convention: `posval.` + event class simple name.

| Event | Topic |
|---|---|
| AuctionImportCompleted | `posval.AuctionImportCompleted` |
| AuctionImportFailed | `posval.AuctionImportFailed` |
| NominationRecorded | `posval.NominationRecorded` |
| NominationDeviationDetected | `posval.NominationDeviationDetected` |
| ImbalanceSettlementComputed | `posval.ImbalanceSettlementComputed` |
| ExchangeFeesComputed | `posval.ExchangeFeesComputed` |
| OperationalAlertRaised | `posval.OperationalAlertRaised` |

All events are written to the outbox table in the same `UnitOfWork.execute()` as the domain state change (Pattern #24, D-9, TR-038). The `OutboxRelayProducer` polls and produces to Kafka after commit.

### 8.6 Idempotency Key Strategy (Pattern #26)

| Consumer/Operation | Idempotency Key | Mechanism |
|---|---|---|
| Auction import | `(tenantId, exchange, biddingZone, deliveryDay)` | AuctionImportSession UNIQUE constraint |
| Individual DA trade capture | `(tradeId, tradeLegId, tradeVersion)` | Existing `DefaultTradeCaptureHandler` idempotency |
| Nomination recording | `(tenantId, balancingGroupId, deliveryDay, intervalStart, nominationVersion)` | Version increment on re-submission |
| Imbalance settlement | `(tenantId, balancingGroupId, deliveryDay, recordVersion)` | Version increment on TSO correction |

---

## S9 -- Guice Wiring

### 9.1 New Guice Module: `DaExchangeModule` (`pv-guice`)

A focused submodule for DA-specific bindings. Installed by `DomainModule` or as a peer module in the injector.

```
// Domain services
bind(AuctionImportService.class).to(DefaultAuctionImportOrchestrator.class).in(Singleton.class);
bind(BlockDecompositionService.class).to(DefaultBlockDecompositionService.class).in(Singleton.class);
bind(NominationService.class).to(DefaultNominationService.class).in(Singleton.class);
bind(ImbalanceSettlementService.class).to(DefaultImbalanceSettlementService.class).in(Singleton.class);
bind(ExchangeFeeService.class).to(DefaultExchangeFeeService.class).in(Singleton.class);
bind(OperationalAlertService.class).to(DefaultOperationalAlertService.class).in(Singleton.class);
bind(PaymentDateService.class).to(DefaultPaymentDateService.class).in(Singleton.class);

// CSV parser and folder poller
bind(AuctionResultParser.class).to(EpexCsvAuctionResultParser.class).in(Singleton.class);
bind(AuctionFolderPoller.class).to(DefaultAuctionFolderPoller.class).in(Singleton.class);
```

### 9.2 PersistenceModule Additions

```
// DA-specific repository adapters
bind(AuctionImportSessionRepository.class).to(JpaAuctionImportSessionRepository.class).in(Singleton.class);
bind(NominationRepository.class).to(JpaNominationRepository.class).in(Singleton.class);
bind(ImbalanceRecordRepository.class).to(JpaImbalanceRecordRepository.class).in(Singleton.class);
bind(OperationalAlertRepository.class).to(JpaOperationalAlertRepository.class).in(Singleton.class);
bind(ExchangeFeeScheduleRepository.class).to(JpaExchangeFeeScheduleRepository.class).in(Singleton.class);
bind(HolidayCalendarRepository.class).to(JpaHolidayCalendarRepository.class).in(Singleton.class);
bind(BlockDefinitionRepository.class).to(JpaBlockDefinitionRepository.class).in(Singleton.class);
bind(BalancingGroupRepository.class).to(JpaBalancingGroupRepository.class).in(Singleton.class);
bind(TARGET2CalendarRepository.class).to(JpaTargetCalendarRepository.class).in(Singleton.class);
```

### 9.3 MarketCalendarPort Wiring

If MarketCalendar is implemented: bind to the existing implementation.
If not yet implemented: bind to `StubMarketCalendarAdapter` in `pv-app` (simulator-scope), with a `MarketCalendarPort` interface in `pv-domain/port/`.

### 9.4 pv-app Exposure (Simulator-Scope)

Spring `@RestController` endpoints in `pv-app` for testing the batch import:

```
POST /api/da/import          -> injector.getInstance(AuctionImportService.class).importAuctionResults()
GET  /api/da/import/{id}     -> injector.getInstance(AuctionImportService.class).getImportSession()
POST /api/da/nominations     -> injector.getInstance(NominationService.class).recordNomination()
POST /api/da/imbalance       -> injector.getInstance(ImbalanceSettlementService.class).computeForDay()
POST /api/da/fees             -> injector.getInstance(ExchangeFeeService.class).computeForDay()
GET  /api/da/alerts           -> injector.getInstance(OperationalAlertService.class).getOpenAlerts()
PUT  /api/da/alerts/{id}/ack -> injector.getInstance(OperationalAlertService.class).acknowledgeAlert()
POST /api/da/import/file     -> injector.getInstance(AuctionImportService.class).importFromFile()  // manual CSV upload
GET  /api/da/import/history  -> injector.getInstance(AuctionImportService.class).getImportHistory()
```

**Folder poller scheduling (simulator-scope):**

```
@Scheduled(fixedDelayString = "${pv.da.poller.interval-ms:60000}")
public void pollAuctionFolder() {
    injector.getInstance(AuctionFolderPoller.class).pollOnce();
}
```

Enabled via `pv.da.poller.enabled=true` in `application.yml`. Disabled by default. The `@Scheduled` annotation is simulator-scope (`pv-app` only) — the production host will use its own scheduling mechanism. D-14 compliant.

All `@Bean` methods delegate to `injector.getInstance(...)`. No `new` calls. D-13 compliant.

---

## S9b -- Instrument Calculation Strategy (Pattern #10 Extension)

### 9b.1 Motivation

The existing settlement pipeline (`SettlementMaterializationJob`) computes `price x volume x intervalHours` for all instruments uniformly. This works because the per-instrument differences are encoded in **data** — price expressions (ConstantLeaf for DA, composite tree for PPAs) and volume references (PROFILE vs FORECAST).

However, DA introduces **instrument-specific post-settlement logic** that cannot be encoded in price expressions or volume references:

| Concern | DA | Intraday (future) | PPA (future) | Forward (future) |
|---|---|---|---|---|
| Fee computation | EPEX trading + clearing fees on gross volume | Different fee schedule, different exchanges | No exchange fees (bilateral) | Exchange fees (EEX) |
| Imbalance settlement | TSO reBAP, per-BG | Same TSO, different scheduling basis | N/A (no scheduling responsibility) | N/A |
| Payment date | ECC D+2 TARGET2 | ECC D+2 TARGET2 | Contract-specific (NET30/NET60) | ECC settlement cycle |
| P&L reference | Self (DA price = 0 P&L) | DA price as benchmark | Contract price vs DA | Forward curve |
| Forward valuation | None (fully realized) | None (fully realized) | Mark-to-market required | Mark-to-market required |
| Block decomposition | EPEX block types | EPEX block types (different set) | N/A (term contract) | N/A |
| Cashflow direction | Exchange (ECC) | Exchange (ECC) | Bilateral counterparty | Exchange / broker |

Without a strategy pattern, each new instrument type would require `if/else` branches scattered across the settlement, fee, imbalance, and P&L services. This violates the open/closed principle and makes instrument onboarding error-prone.

### 9b.2 Design: InstrumentCalculationStrategy (sealed interface, Pattern #10 + #5)

A sealed interface in `pv-domain/port/service/` that encapsulates all instrument-specific calculation behavior. Each instrument type provides a concrete implementation. The settlement pipeline dispatches to the correct strategy based on `originType` (or a new `instrumentType` field on the ledger entry).

```
// pv-domain/port/service/
sealed interface InstrumentCalculationStrategy
    permits DaExchangeSpotStrategy, IntraDayStrategy, PpaStrategy, ForwardStrategy {

    /** Instrument identifier for dispatch. */
    String instrumentType();

    /** Post-settlement hooks — called after core settlement cells are materialized. */
    PostSettlementResult postSettle(PostSettlementContext ctx);

    /** Fee computation for a delivery day. Returns empty if instrument has no fees. */
    Optional<FeeResult> computeFees(FeeContext ctx);

    /** Payment date computation. */
    LocalDate computePaymentDate(LocalDate deliveryDay, PaymentDateContext ctx);

    /** P&L reference price for a given interval. Returns empty if self-referencing. */
    Optional<BigDecimal> referencePriceForInterval(Instant intervalStart, PnlContext ctx);

    /** Whether this instrument requires forward/MtM valuation. */
    boolean requiresForwardValuation();

    /** Cashflow counterparty identifier. */
    String counterpartyId(CounterpartyContext ctx);
}
```

### 9b.3 Context Records (Pattern #3)

Each method receives a focused context record carrying only what it needs — no god-object parameter:

```
// pv-domain/port/service/
record PostSettlementContext(
    String tenantId,
    String portfolioId,
    String deliveryPointId,
    LocalDate deliveryDay,
    List<SettlementCell> settledCells,
    MarketDataPort marketDataPort
)

record FeeContext(
    String tenantId,
    String exchange,
    LocalDate deliveryDay,
    BigDecimal grossVolumeMwh,
    ExchangeFeeScheduleRepository feeScheduleRepo
)

record PaymentDateContext(
    TARGET2CalendarRepository target2Calendar
    // future: PPA contract payment terms, broker settlement cycles
)

record PnlContext(
    String tenantId,
    String deliveryPointId,
    MarketDataPort marketDataPort
)

record CounterpartyContext(
    String tenantId,
    String exchange
    // future: PPA counterparty, broker identity
)
```

### 9b.4 DA Implementation

```
// pv-domain/service/instrument/
public final class DaExchangeSpotStrategy implements InstrumentCalculationStrategy {

    @Override public String instrumentType() { return "DA_EXCHANGE_SPOT"; }

    @Override public PostSettlementResult postSettle(PostSettlementContext ctx) {
        // DA has no post-settlement adjustments — all computed in core pipeline
        return PostSettlementResult.noOp();
    }

    @Override public Optional<FeeResult> computeFees(FeeContext ctx) {
        // Delegates to ExchangeFeeService (S5.2)
        // Fee = grossVolumeMwh * effectiveRate (TRADING + CLEARING)
        // Returns FeeResult with line items
    }

    @Override public LocalDate computePaymentDate(LocalDate deliveryDay, PaymentDateContext ctx) {
        // D+2 TARGET2 business days
        return ctx.target2Calendar().nthBusinessDayAfter(deliveryDay, 2);
    }

    @Override public Optional<BigDecimal> referencePriceForInterval(Instant intervalStart, PnlContext ctx) {
        // DA self-references: P&L = 0 against own price
        return Optional.empty();
    }

    @Override public boolean requiresForwardValuation() { return false; }

    @Override public String counterpartyId(CounterpartyContext ctx) { return "ECC"; }
}
```

### 9b.5 Strategy Registry and Dispatch (Pattern #7)

```
// pv-domain/service/instrument/
public class InstrumentStrategyRegistry {

    private final Map<String, InstrumentCalculationStrategy> strategies;

    @Inject
    public InstrumentStrategyRegistry(Set<InstrumentCalculationStrategy> strategies) {
        this.strategies = strategies.stream()
            .collect(Collectors.toMap(
                InstrumentCalculationStrategy::instrumentType,
                Function.identity()));
    }

    public InstrumentCalculationStrategy forInstrument(String instrumentType) {
        var strategy = strategies.get(instrumentType);
        if (strategy == null) {
            throw new IllegalArgumentException(
                "No calculation strategy registered for instrument: " + instrumentType);
        }
        return strategy;
    }
}
```

### 9b.6 Guice Wiring (Multibinder)

```
// pv-guice/DaExchangeModule.java (addition to S9.1)
Multibinder<InstrumentCalculationStrategy> strategyBinder =
    Multibinder.newSetBinder(binder(), InstrumentCalculationStrategy.class);
strategyBinder.addBinding().to(DaExchangeSpotStrategy.class);
// Future: strategyBinder.addBinding().to(IntraDayStrategy.class);
// Future: strategyBinder.addBinding().to(PpaStrategy.class);

bind(InstrumentStrategyRegistry.class).in(Singleton.class);
```

When a new instrument is onboarded (Intraday, PPA, Forward, etc.), the only wiring change is one `addBinding()` line in the Guice module. The registry auto-discovers all bound strategies.

### 9b.7 Integration with Existing Pipeline

The strategy does **not** replace the core settlement pipeline. The flow is:

```
TradeCapture
    |
    v
SettlementMaterializationJob.execute()        <-- UNCHANGED (Pattern #15, final)
    |  (produces SettlementCells with price x volume x intervalHours)
    |
    v
SettlementComputed event
    |
    v
InstrumentPostSettlementConsumer (NEW Kafka consumer)
    |
    +-- Lookup instrumentType from PositionLedgerEntry.originType
    +-- InstrumentStrategyRegistry.forInstrument(instrumentType)
    +-- strategy.postSettle(ctx)          <-- instrument-specific post-processing
    +-- strategy.computeFees(ctx)         <-- if present, persist fee records
    +-- strategy.computePaymentDate(...)  <-- compute and attach to cashflow
    +-- Publish InstrumentPostSettlementCompleted via outbox
```

This is an **additive consumer** downstream of `SettlementComputed`. It does not modify `AbstractMaterializationJob.execute()` (which is `final` per CLAUDE.md). The core settlement pipeline remains instrument-agnostic; the strategy layer handles the instrument-specific tail.

### 9b.8 InstrumentType on Position Ledger Entry

The existing `originType` field (`MANUAL_ENTRY`, `EXCHANGE_FILL`, `CASCADE_CHILD`, etc.) identifies the **source** of a trade, not the **instrument**. For strategy dispatch, we need an `instrumentType` field:

| originType | instrumentType | Notes |
|---|---|---|
| `EXCHANGE_FILL` | `DA_EXCHANGE_SPOT` | DA auction result |
| `EXCHANGE_FILL` | `INTRADAY_CONTINUOUS` | Future: intraday trade |
| `MANUAL_ENTRY` | `PPA` | Future: PPA contract |
| `MANUAL_ENTRY` | `FORWARD` | Future: forward/futures |

**Option A (preferred):** Add `instrumentType` as a new String field on `PositionLedgerEntry` (nullable, defaults to legacy behavior for existing trades). The `TradeCapture` command already sets `originType`; adding `instrumentType` is a one-field extension. The DA import orchestrator sets `instrumentType = "DA_EXCHANGE_SPOT"` on every `TradeCapture` it issues.

**Option B:** Derive `instrumentType` from a combination of `originType` + `deliveryPointId` + configuration. More fragile, avoids schema change.

Option A is recommended. It is explicit, auditable, and does not depend on inference rules that may break when new instrument types share the same `originType`.

### 9b.9 Constraint Compatibility

| Constraint | Status |
|---|---|
| **D-1** (Ledger grain) | Compatible. `instrumentType` is a new field on the existing ledger entry, not a structural change. |
| **D-2** (Price = expression ref) | Compatible. Strategy does not alter price evaluation — it operates post-settlement. |
| **D-13** (No Spring in library) | Compatible. `InstrumentCalculationStrategy` is a sealed interface in `pv-domain`. Registry and implementations are in `pv-domain/service/`. Guice wiring in `pv-guice`. |
| **D-14** (No simulator patterns in library) | Compatible. No hardcoded instrument types in library code — strategies are registry-driven. |
| **Pattern #15** (`AbstractMaterializationJob.execute()` is `final`) | Compatible. The strategy operates **after** materialization, not during it. |
| **Pattern #5** (Sealed hierarchy) | Used. `InstrumentCalculationStrategy` is sealed — compiler enforces exhaustive handling when pattern-matching over strategy types. |
| **ADR-001 Pattern #10** (Strategy — Price Evaluation) | Extended. This is a sibling strategy for instrument-specific post-settlement logic, not a replacement for price evaluation. |

### 9b.10 Future Instrument Onboarding Checklist

When adding a new instrument type (e.g., Intraday Continuous):

1. Create `IntraDayStrategy implements InstrumentCalculationStrategy` in `pv-domain/service/instrument/`
2. Implement each method (fees, payment date, reference price, forward valuation flag, counterparty)
3. Add `strategyBinder.addBinding().to(IntraDayStrategy.class)` in the instrument's Guice module
4. Add the `instrumentType` constant (e.g., `"INTRADAY_CONTINUOUS"`)
5. Write unit tests for the strategy methods
6. The pipeline, registry, consumer, and dispatch are unchanged

No `if/else` branches. No modifications to existing strategies. Open for extension, closed for modification.

---

## S10 -- Cross-Cutting

### 10.1 Tenant Handling (Pattern #32, D-14)

All new repository ports accept `tenantId` as the first parameter. All new tables include `tenant_id` as the leading column with RLS policies (production hosting layer responsibility). System-level reference data (HolidayCalendar, BlockDefinition, TARGET2Calendar) is intentionally tenant-independent -- these are shared facts.

### 10.2 Bitemporal Invariants

**Bitemporal entities in this spec: NONE new.** The DA pipeline creates `PositionLedgerEntry` instances which ARE bitemporal -- but that is handled by the existing S1 infrastructure. All new entities in this spec (AuctionImportSession, NominationRecord, ImbalanceRecord, OperationalAlert) are NOT bitemporal. They use simpler versioning:

- `AuctionImportSession`: status state machine, no versioning needed (one session per delivery day per zone).
- `NominationRecord`: `nominationVersion` monotonic integer for re-submissions.
- `ImbalanceRecord`: `recordVersion` monotonic integer for TSO corrections.
- `OperationalAlert`: status transitions (OPEN -> ACKNOWLEDGED -> RESOLVED), not versioned.

Rationale: These entities do not need "what was our view at knowledge time T?" reconstruction. They are operational records, not regulatory-grade audit entities. The position ledger entries (which ARE bitemporal) provide the regulatory reconstruction capability.

### 10.3 Transaction Boundaries

**Batch import:** The entire `importAuctionResults()` operation runs within a single `UnitOfWork.execute()`. This is critical:
- All `TradeCapture` commands are executed within the same transaction.
- All outbox rows (one `PositionEntryCaptured` per trade-leg + one `AuctionImportCompleted`) are written atomically.
- If any trade capture fails, the entire import rolls back, and the session status remains PENDING.

For a 96-interval DA import, this means ~96 `PositionLedgerEntry` inserts + ~96 outbox rows in one transaction. This is well within PostgreSQL's transaction size limits (the existing PPA pipeline handles similar-scale batch operations).

**Imbalance settlement:** Single `UnitOfWork.execute()` per delivery day per balancing group. Typically 92-100 `ImbalanceRecord` inserts + 1 outbox row.

### 10.4 Cache Invalidation

DA trades flow through the existing settlement and rollup pipeline. Cache invalidation is handled by existing mechanisms:

- `SettlementComputed` -> `CacheInvalidationHandler` invalidates S6/S6b caches.
- `MarketDataUpdated` -> `MarketDataUpdatedConsumer` triggers revaluation via S8 dependency index.
- New DA-specific caches: NONE in v1. DA data volumes are small enough (max ~100 intervals per delivery day) that cache-aside is not needed for the nomination/imbalance query paths.

### 10.5 Immutability of Auction Results (DA-PRC-01, REMIT Art. 8(5))

DA clearing prices, once imported, are immutable. This is enforced at multiple layers:

1. **Application layer:** `AuctionImportOrchestrator` checks session status before import. If session.status == IMPORTED, the import is a no-op (idempotent return).
2. **Price expression layer:** `ConstantLeaf` price expressions are immutable by construction (Pattern #34).
3. **Market data layer:** Fixings stored via `MarketDataRepository.saveFixing()` are append-only. No `updateFixing()` method exists on the port.
4. **Correction path:** If EPEX issues a correction (auction re-run), a new `AuctionImportSession` is created with incremented `tradeVersion`, and the existing `DefaultTradeCaptureHandler` supersession logic closes the old entries' `knownTo` and creates new versions. The original data is preserved for regulatory reconstruction.

---

## S10a -- Performance Profile

### 10a.1 Pagination Strategy

| Endpoint / Query | Strategy | Default / Max Page Size |
|---|---|---|
| `GET /api/da/alerts` | Offset-based | 50 / 200. Alert count per tenant per day is bounded (~20 max). |
| Imbalance monthly aggregate | No pagination | Max 100 daily summaries per month. Response < 20KB. |
| Nomination list by delivery day | No pagination | Max 100 intervals per day. Response < 15KB. |
| Auction import session detail | Single object | N/A |

### 10a.2 Response Size Budgets

| Response | Size Budget | Rationale |
|---|---|---|
| Auction import result | < 50KB | 96 trade IDs + session metadata |
| Nomination deviation list | < 15KB | Max 100 intervals |
| Imbalance settlement day | < 20KB | Max 100 records |
| Imbalance monthly summary | < 30KB | 30 day summaries + interval breakdown |
| Alert dashboard | < 30KB | 50 alerts default page |

### 10a.3 Query Performance

**Auction import idempotency check:** Single UNIQUE index lookup on `(tenant_id, exchange, bidding_zone, delivery_day)`. O(1).

**Nomination comparison (traded vs nominated):** Loads traded positions via `PositionLedgerRepository.findAllByDeliveryRange()` (existing index `idx_ple_delivery_range`) for a single delivery day (23-25 hours). Then loads nominations for the same day. Both result sets are small (max 100 intervals each). In-memory comparison.

**Imbalance monthly aggregation:** Query on `(tenant_id, balancing_group_id, delivery_day BETWEEN start AND end)`. Covered by the composite index on `imbalance_record`. Max ~3,000 rows per BG per month (100 intervals/day * 31 days). Aggregation in JPQL `SUM()`.

**Alert dashboard:** Query on `(tenant_id, status, severity)` with `ORDER BY raised_at DESC LIMIT 50`. Covered by the composite index.

### 10a.4 Redis Cache

No new Redis cache keys for DA v1. DA data volumes per delivery day are small (max 100 intervals). The existing settlement cell and rollup caches handle the downstream data. If nomination or imbalance query latency becomes a concern at scale, a cache layer can be added in v2 with TTL = 5 min and invalidation on `NominationRecorded` / `ImbalanceSettlementComputed` events.

### 10a.5 Connection Pool

No new DataSource or connection path. All queries use the existing writer pool (10) and reader pool (20) via `DualHikariDataSourceRouter`. The batch import transaction is write-heavy but short-lived (96 inserts in ~200ms).

---

## S10b -- Real-Time Push

### 10b.1 Applicability

**Partially applicable.** Two push paths are relevant for DA:

1. **Auction import progress:** Operations triggers a batch import and wants to see the status transition (PENDING -> VALIDATING -> IMPORTING -> IMPORTED). This is a short-lived workflow (seconds to minutes), not a continuous stream.

2. **Operational alerts:** New alerts should appear on the dashboard without manual refresh.

### 10b.2 Design

**v1: Polling only.** Both use cases are satisfied by TanStack Query `refetchInterval`:

- Import status: poll `GET /api/da/import/{id}` every 2 seconds while status is PENDING/VALIDATING/IMPORTING. Stop polling on terminal state.
- Alert dashboard: poll `GET /api/da/alerts` every 30 seconds. Badge counts update on poll.

**v2 (future):** SSE endpoint `GET /api/da/alerts/stream` subscribed to `posval.OperationalAlertRaised` Kafka topic, filtered by tenant. Reconnection delivers full snapshot (not delta). Backpressure: latest-value-wins, max 1 push per 5 seconds per subscription.

The push path is an optimization. The REST endpoints are the source of truth. No push-only data.

---

## S10c -- DST Handling

### 10c.1 Time Zone Convention

All timestamps in API responses are UTC (`Instant`). The API accepts an optional `timezone` parameter (default `Europe/Berlin`) for endpoints that return daily aggregates, so the server computes day boundaries correctly.

### 10c.2 Interval Counts on DST Transition Days

The `MarketCalendarPort.intervalsForDay()` method is the sole authority (FR-024/FR-025). For the `Europe/Berlin` zone:

| Day Type | QH Intervals | Hourly Intervals |
|---|---|---|
| Normal | 96 | 24 |
| Spring-forward (last Sun Mar) | 92 | 23 |
| Fall-back (last Sun Oct) | 100 | 25 |

### 10c.3 Batch Import Validation on DST Days

The import orchestrator calls `MarketCalendarPort.intervalCount(zone, deliveryDay, granularity)` to determine the expected interval count. If the exchange report contains a different number of contracts, validation fails.

**Spring-forward:** Expected 92 QH. If a contract references the non-existent hour 02:00-03:00 CET, it is rejected with a validation error.

**Fall-back:** Expected 100 QH. The duplicate hour 02:00-03:00 appears twice. The import parser must distinguish them by UTC offset (CEST = UTC+2, CET = UTC+1). Both occurrences are valid, separate contracts. Storage uses UTC `Instant` boundaries, so there is no ambiguity: the first 02:00 is `00:00 UTC`, the second 02:00 is `01:00 UTC`.

### 10c.4 Block Decomposition on DST Days

`BlockDecompositionService.decompose()` delegates to `MarketCalendarPort.intervalsForDay()` which returns DST-correct intervals. A baseload block on spring-forward produces 92 intervals (23 hours), on fall-back produces 100 intervals (25 hours). Peak/off-peak classification uses `MarketCalendarPort.isPeakInterval()`.

### 10c.5 Delivery Day Boundaries

A "delivery day" in CET is:
- Normal: `[22:00 UTC previous day, 22:00 UTC]` (winter) or `[21:00 UTC previous day, 21:00 UTC]` (summer)
- Spring-forward: `[23:00 UTC D-1, 22:00 UTC D]` (23 hours)
- Fall-back: `[22:00 UTC D-1, 23:00 UTC D]` (25 hours)

All queries for "delivery day" must compute the correct UTC boundaries. This is the responsibility of `MarketCalendarPort.deliveryDayBoundaries()`.

### 10c.6 Gate Closure Alignment

DA auction gate closure is 12:00 CET (always CET, not CEST). Nomination gate closure for DE is 14:30 CET. The `OperationalAlertService` computes these as UTC instants using `ZonedDateTime.of(deliveryDay.minusDays(1), LocalTime.of(12, 0), ZoneId.of("Europe/Berlin"))` for auction closure and similarly for nomination closure. On DST transition days, `java.time` handles the offset correctly.

---

## S11 -- Regulatory Impact

### REMIT (Regulation 1227/2011)

**Applicable.** DA exchange trades on EPEX Spot are wholesale energy products reportable under REMIT Art. 8(1).

| Obligation | Platform impact |
|---|---|
| Transaction reporting (Art. 8(1)) | Each DA trade captured via `TradeCapture` produces a `PositionLedgerEntry` with all fields needed for REMIT reporting: tradeId (UTI), tenantId (maps to LEI via tenant config -- OQ-9), deliveryPointId (bidding zone), price (via priceExpressionId -> ConstantLeaf), quantity, deliveryStart/End, originType = "EXCHANGE_FILL" (maps to venue MIC: XEPC). Report generation is a downstream consumer of ledger data -- not built in this spec. |
| Record keeping (Art. 8(5)) | Auction results are immutable after import (S10.5). Position ledger entries are bitemporal and append-only (FR-006). 5-year retention supported by the hot-store retention policy (existing). |
| Position reconstruction | Existing S1 bitemporal ledger supports as-of reconstruction (FR-007). DA positions reconstruct identically. |

### EMIR

**Not applicable.** DA spot contracts with physical delivery within T+2 are exempt from EMIR reporting obligations.

### MiFID II / MiFIR

**Partially applicable.** The platform retains execution timestamp, price, quantity, venue MIC, and delivery period on each `PositionLedgerEntry` -- sufficient for the firm's RTS 22 records. The exchange (EPEX) submits the transaction report to the NCA. No platform-side submission.

---

## S12 -- Testing Strategy

### 12.1 Unit Tests (per module, `*Test.java`)

| Test Class | Module | What it tests |
|---|---|---|
| `AuctionImportOrchestratorTest` | pv-domain | Batch import logic: validation, idempotency, block decomposition dispatch, trade capture delegation. Mock all ports. |
| `BlockDecompositionServiceTest` | pv-domain | Baseload/peak/off-peak/custom decomposition. DST transition days (92/100 intervals). Holiday exclusion. Hand-mocked MarketCalendarPort + HolidayCalendarRepository. |
| `NominationServiceTest` | pv-domain | Nomination recording, deviation detection against traded volumes. Mock PositionLedgerRepository + NominationRepository. |
| `ImbalanceSettlementServiceTest` | pv-domain | Imbalance formula: over-delivery, under-delivery, negative prices, DST days. NumericPrecision rounding (MONETARY scale 4). Mock NominationRepository + MarketDataPort. |
| `ExchangeFeeServiceTest` | pv-domain | Gross volume calculation (absolute, not netted), effective-date fee lookup, tiered fees. Mock repositories. |
| `PaymentDateServiceTest` | pv-domain | TARGET2 calendar: D+2 roll, holiday skip, weekend skip. Mock TARGET2CalendarRepository. |
| `OperationalAlertServiceTest` | pv-domain | Alert lifecycle: raise, acknowledge, resolve. Category/severity filtering. |
| `AuctionImportSessionStatusTest` | pv-domain | State machine transitions: PENDING -> VALIDATING -> IMPORTED. Invalid transitions rejected. Pattern #16. |
| `EpexCsvAuctionResultParserTest` | pv-persistence | Valid CSV parsing (96 intervals, correct prices/volumes). Malformed CSV (missing columns, bad numbers). Negative clearing prices. DST days (92/100 intervals). File naming convention validation. Header metadata extraction (exchange, bidding zone, delivery day). |
| `DefaultAuctionFolderPollerTest` | pv-domain | File discovery in inbox. Successful parse → move to processed. Failed parse → move to failed. Empty inbox → no-op. Multiple files processed in delivery-day order. Mock AuctionResultParser + AuctionImportService. |

### 12.2 Integration Tests (pv-integration-tests, `*IT.java`, Testcontainers PG16)

| Test Class | What it tests |
|---|---|
| `AuctionImportIT` | Full pipeline: parse batch -> validate -> block decompose -> trade capture -> settlement materialization -> rollup. Verify 96 settlement cells created. Verify outbox rows written. Verify idempotent re-import. |
| `DstAuctionImportIT` | Spring-forward (92 intervals) and fall-back (100 intervals) delivery days. Verify correct interval count in settlement cells. Verify delivery day boundaries in UTC. |
| `NominationDeviationIT` | Record nominations, compare with traded volumes, verify alert creation on deviation. |
| `ImbalanceSettlementIT` | Full imbalance flow: nominations + actuals + reBAP -> imbalance records. Monthly aggregation query. |
| `ExchangeFeeIT` | Fee computation with effective-dated schedule. Verify MONETARY precision (scale 4). |
| `AuctionImmutabilityIT` | Verify that re-import with same delivery day returns existing session (idempotent). Verify that price expressions are not modified. |

**NOT H2.** All integration tests use Testcontainers PostgreSQL 16. This is non-negotiable (CLAUDE.md testing strategy).

### 12.3 Contract Tests

New port/adapter pairs:

| Port | Adapter | Contract test |
|---|---|---|
| AuctionImportSessionRepository | JpaAuctionImportSessionRepository | `AuctionImportSessionRepositoryContractTest` |
| NominationRepository | JpaNominationRepository | `NominationRepositoryContractTest` |
| ImbalanceRecordRepository | JpaImbalanceRecordRepository | `ImbalanceRecordRepositoryContractTest` |
| OperationalAlertRepository | JpaOperationalAlertRepository | `OperationalAlertRepositoryContractTest` |
| ExchangeFeeScheduleRepository | JpaExchangeFeeScheduleRepository | `ExchangeFeeScheduleRepositoryContractTest` |
| TARGET2CalendarRepository | JpaTargetCalendarRepository | `TARGET2CalendarRepositoryContractTest` |
| AuctionResultParser | EpexCsvAuctionResultParser | `EpexCsvAuctionResultParserContractTest` |

---

## S13 -- Constraint Compatibility

| Constraint | Status | Rationale |
|---|---|---|
| **D-1** (Ledger grain = trade-leg x delivery-month) | **Compatible.** DA trades create one `PositionLedgerEntry` per interval per delivery month. A single-interval DA contract produces exactly one entry (interval fits within one month). |
| **D-2** (Price = expression ref; fixed = degenerate) | **Compatible.** DA clearing prices create `ConstantLeaf` price expressions -- the degenerate case documented in D-2 and Pattern #5. |
| **D-3** (Forward marks ephemeral; settlement bitemporal) | **Not applicable.** DA trades have no forward/MtM valuation (all P&L is realized). Settlement cells are created via the existing bitemporal-compatible pipeline. |
| **D-4** (Optimized version-binding; active_leaves) | **Compatible.** `ConstantLeaf` produces a single active leaf. The `active_leaves` and `input_version_set` on SettlementCell are populated by the existing pipeline. |
| **D-5** (Peak is interval-dimension data) | **Compatible.** Block decomposition uses `MarketCalendarPort.isPeakInterval()` for peak/off-peak classification. Peak status is never stored on position rows. |
| **D-6** (Dual units MW+MWh in cache/rollups only) | **Compatible.** Position ledger entries store MW. Settlement cells store both MW and MWh. Rollups aggregate. Same as existing instruments. |
| **D-7** (Batch authoritative, re-derive idempotency) | **Compatible.** Auction import is idempotent by session UNIQUE constraint. Trade capture is idempotent by existing `DefaultTradeCaptureHandler` check. Settlement materialization is idempotent by `TradeCapturedConsumer.alreadyProcessed()`. |
| **D-8** (Entity/measure distinction) | **Compatible.** `PositionLedgerEntry` is an entity. `SettlementCell` is a measure. New entities (AuctionImportSession, NominationRecord) are correctly classified -- see S4.3 notes. |
| **D-9** (Outbox-in-same-transaction) | **Compatible.** All new events are published via `DomainEventPublisher` (which writes to the outbox table) within `UnitOfWork.execute()`. No direct Kafka produce. |
| **D-10** (All interval structure via MarketCalendar) | **Compatible.** Block decomposition and interval generation delegate to `MarketCalendarPort`. No timestamp arithmetic. |
| **D-11** (Unified volume: VolumeReference x multiplier) | **Compatible.** DA trades use `VolumeReference` with `multiplier = 1.0` and a dedicated PROFILE series. This is the degenerate fixed-profile case already supported. |
| **D-12** (S6b trade_interval_cache: optional, rebuildable) | **Compatible.** DA trades populate S6b via the existing `TradeIntervalCacheRebuilder`. Cache is optional and rebuildable. |
| **D-13** (Library-first, no Spring in library modules) | **Compatible.** All new domain services, ports, and adapters are in pv-domain/pv-persistence/pv-guice. No Spring annotations. Only `@jakarta.inject.Inject`. REST endpoints are in pv-app only. |
| **D-14** (Simulator patterns only in pv-app) | **Compatible.** No hardcoded tenant IDs in library modules. `StubMarketCalendarAdapter` (if needed) lives in pv-app. All library code accepts `tenantId` as parameter. |

---

## S14 -- Open Items

| # | Item | Blocking? | Owner |
|---|---|---|---|
| OI-1 | **MarketCalendar implementation status (OQ-3).** Block decomposition and DST-correct import depend on `MarketCalendarPort`. If not implemented, must be built as a prerequisite or a stub must be provided. | **YES** for DA-VOL-02 | Implementation team |
| OI-2 | **RESOLVED — EPEX feed format (OQ-1).** CSV format. `EpexCsvAuctionResultParser` adapter in `pv-persistence` parses CSV files into `AuctionResultBatch`. `AuctionFolderPoller` watches a configured inbox directory. See S6.2 and S6.3. | Resolved | — |
| OI-3 | **RESOLVED — One deal per 15-min interval (OQ-2).** Confirmed by stakeholder. Each 15-min interval contract is a separate tradeId. See A-2 (status: DECISION). | Resolved | — |
| OI-4 | **Cross-instrument P&L linkage (OQ-8).** DA-VAL-02 requires linking ID adjustments to DA positions. This needs a trade-linkage mechanism. Deferred. | **NO** for v1 | Functional Expert |
| OI-5 | **Configurable reference price (OQ-7).** The existing `marketPriceExpressionId` on the ledger may serve this purpose. Configuration UI and portfolio-level settings are deferred. | **NO** for v1 | Functional Expert |
| OI-6 | **Imbalance price sign convention (OQ-12).** The design is formula-agnostic (`(nominated - actual) * imbalancePrice`), but the interpretation of positive/negative reBAP must be confirmed for correct settlement. | **YES** for DA-SET-04 | Regulatory / Functional Expert |
| OI-7 | **Tenant EPEX member identity (OQ-9).** Needed for REMIT transaction reporting. Not blocking for position/valuation, but blocking for the REMIT report generator (separate subsystem). | **NO** for v1 | Compliance |
| OI-8 | **Cashflow generation (OQ-6).** PORT-10 (treasury/liquidity module) existence TBD. This spec publishes events (`ExchangeFeesComputed`, `ImbalanceSettlementComputed`) that a treasury module can consume. No `CashflowRecord` entity is created within pv-domain. | **NO** for v1 | Product |
| OI-9 | **Alert threshold configuration (OQ-DA-OPS-3).** "High imbalance cost" and "settlement exceeds daily threshold" thresholds need values. Per-tenant or system-wide? | **NO** for v1 (hardcode sensible defaults) | Product |
| OI-10 | **ExchangeFeeSchedule: volume-tiered fees (DA-SET-03 Scenario 3).** The spec supports tiered fees via `memberTier` field. The cumulative monthly volume computation (to determine which tier applies) requires querying all settlement cells for the month. This query path needs design if tiered fees are v1 scope. | **NO** for v1 (flat rate only; tiers in v2) | Functional Expert |
| OI-11 | **`PriceExpressionRepository.save()` method.** The existing port interface (`PriceExpressionRepository`) only has `findById()`. The batch import needs to save new `ConstantLeaf` expressions. A `save(PriceExpression)` method must be added to the port. | **YES** for v1 | Implementation team |
| OI-12 | **`MarketDataRepository` write port.** The existing `MarketDataPort` is read-only. DA clearing price ingestion needs a write path (`saveFixing`). Either extend `MarketDataPort` or define a separate `MarketDataWritePort`. The existing `MarketDataRepository` port needs inspection. | **YES** for v1 | Implementation team |

---

## S15 -- Phasing / Incremental Build Plan

### Phase 1: Core Import Pipeline (MVP)

**Goal:** A DA auction result can be imported and flow through the existing position/settlement/rollup pipeline end-to-end.

**Scope:**
- `AuctionResultBatch` command and `AuctionImportSession` entity
- `AuctionResultParser` port + `EpexCsvAuctionResultParser` adapter (CSV parsing, S6.2)
- `AuctionFolderPoller` service (inbox directory polling, S6.3)
- `AuctionImportOrchestrator` with validation and idempotency
- `ConstantLeaf` price expression creation for DA clearing prices
- DA clearing price storage as S4 fixings
- Trade capture via existing `TradeCaptureHandler` (one `TradeCapture` per 15-min interval — A-2 confirmed)
- Existing downstream pipeline: `PositionEntryCaptured` -> `SettlementMaterializationJob` -> `SettlementComputed` -> rollup
- `PriceExpressionRepository.save()` addition (OI-11)
- `MarketDataWritePort` or equivalent (OI-12)
- `StubMarketCalendarAdapter` in pv-app if MarketCalendar not yet available
- REST endpoint in pv-app for testing + scheduled folder poller in pv-app

**Deliverables:** AuctionImportSessionRepository, EpexCsvAuctionResultParser, DefaultAuctionFolderPoller, AuctionImportOrchestrator, Guice wiring, pv-app poller scheduling, unit tests (including CSV parse tests), integration test.

### Phase 2: Block Decomposition + DST

**Goal:** Block orders (baseload, peak, off-peak, custom) are correctly decomposed, including on DST transition days.

**Scope:**
- `BlockDecompositionService`
- `HolidayCalendar` and `BlockDefinition` reference data entities + repositories
- `MarketCalendarPort` implementation (or dependency on concurrent MarketCalendar build)
- DST-correct interval generation for spring-forward and fall-back days
- Unit tests with DST edge cases

### Phase 3: Nomination Tracking

**Goal:** Operations can record nominations and see deviations from traded volumes.

**Scope:**
- `NominationRecord` entity + repository
- `NominationService` with deviation detection
- `BalancingGroup` and `AssetToBalancingGroupMapping` reference data
- `NominationDeviationDetected` event
- Operational alerts for nomination deviations

### Phase 4: Exchange Fees + Payment Dates

**Goal:** Exchange fees are computed per delivery day; payment dates follow TARGET2 calendar.

**Scope:**
- `ExchangeFeeSchedule` entity + repository
- `ExchangeFeeService` (flat rate v1, tiered v2)
- `TARGET2Calendar` entity + repository
- `PaymentDateService`
- `ExchangeFeesComputed` event

### Phase 5: Imbalance Settlement

**Goal:** Per-interval imbalance settlement from TSO data, with monthly aggregation.

**Scope:**
- `ImbalanceRecord` entity + repository
- `ImbalanceSettlementService`
- reBAP price ingestion via S4 MarketDataPort
- Monthly aggregation query
- `ImbalanceSettlementComputed` event
- Depends on OI-6 (sign convention resolution)

### Phase 6: Operational Alerts Dashboard

**Goal:** Consolidated alert view across all DA lifecycle events.

**Scope:**
- `OperationalAlert` entity + repository
- `OperationalAlertService`
- Alert-raising logic wired into all DA services (import, nomination, imbalance, fees)
- REST endpoints for alert dashboard
- SSE push path (v2)


---

## S16 -- UI Technical Design (DA-UI-01 through DA-UI-09)

### 16.0 Layer Classification

This section is **simulator-scope** (`pv-ui` + `pv-app` REST endpoints). The UI is a React SPA consuming the REST endpoints defined in S9.4 plus additional read endpoints defined in S16.2. All data flows through the existing `apiFetch` client with tenant injection. No library-scope (`pv-domain` / `pv-persistence`) changes are introduced by this section.

**Subsystems read (UI perspective):** S5a (settlement cells via existing endpoints), S7 (rollups via existing endpoints), plus the DA-specific domain entities from S4.3 (AuctionImportSession, NominationRecord, ImbalanceRecord, OperationalAlert, ExchangeFeeSchedule) via the S9.4 endpoints.

### 16.1 UI Component Architecture

#### 16.1.1 Component Tree

```
AppShell (existing)
  +-- routeTree (amended -- see S16.6)
       +-- DashboardPage (existing, /dashboard/:portfolioId)
       +-- DaLayout (/da -- new)
            +-- DaNavTabs (tab group: Import | Settlement | Nominations | Imbalance | Fees | Alerts)
            +-- DaKpiStrip (DA-UI-07, always visible)
            +-- <Outlet> (child route content)
                 +-- DaImportPage (/da/import)
                 |    +-- DaImportHistoryTable
                 |    +-- DaImportDetailPanel
                 |         +-- ProgressStepper (new primitive)
                 |         +-- StatusBadge (existing, extended status set)
                 +-- DaSettlementPage (/da/settlement)
                 |    +-- DaSettlementFilterBar
                 |    +-- DaSettlementGrid
                 |    |    +-- DstSeparatorRow (new primitive)
                 |    |    +-- NumericCell (existing)
                 |    |    +-- StatusBadge (existing, extended)
                 |    +-- DaSettlementSummaryRow
                 +-- DaNominationPage (/da/nominations)
                 |    +-- DaNominationFilterBar
                 |    +-- DaNominationGrid
                 |    |    +-- DeviationCell (new primitive)
                 |    |    +-- NumericCell (existing)
                 |    +-- DaNominationSummaryBar
                 +-- DaImbalancePage (/da/imbalance)
                 |    +-- DaImbalanceDailyGrid
                 |    |    +-- NumericCell (existing)
                 |    +-- DaImbalanceMonthlyGrid
                 |    +-- DaImbalanceSummaryBar
                 +-- DaFeesPage (/da/fees)
                 |    +-- DaFeeBreakdownTable
                 |    |    +-- NumericCell (existing)
                 +-- DaAlertsPage (/da/alerts)
                      +-- CategoryBadgeStrip (new primitive)
                      +-- DaAlertFilterBar
                      +-- DaAlertList
                      |    +-- DaAlertRow (expandable)
                      |    +-- StatusBadge (existing, extended)
                      +-- SkeletonTable (existing)
                      +-- EmptyState (existing)
```

#### 16.1.2 Data Flow

All DA pages follow the same data flow pattern established by `DashboardPage`:

1. **Route params** (`deliveryDay`, `zone`, `balancingGroupId`) drive query parameters.
2. **Zustand stores** hold filter state (`useDaFilters`) and selection state (`useDaSelection`).
3. **TanStack Query hooks** (`useDaQueries.ts`) fetch data from `/api/da/*` endpoints with Zod validation.
4. **SSE invalidation** reuses the existing `useRealtimeInvalidation` hook. New `ChangeType` values (`DA_IMPORT_COMPLETED`, `DA_ALERT_RAISED`) are added to the `INVALIDATION_MAP` to invalidate DA query keys.
5. **Tenant isolation** is enforced identically to the existing dashboard: `useTenantStore` injects `tenantId` into every query key and every API call via `apiFetch`.

#### 16.1.3 State Management

| Store | Purpose | Pattern |
|---|---|---|
| `useDaFilters` (Zustand) | Delivery day, zone, balancing group, date range filters. Persisted to URL search params. | Same pattern as `useDashboardFilters` |
| `useDaSelection` (Zustand) | Selected import session, selected alert, expanded row tracking. | Same pattern as `useDashboardSelection` |
| `useTenantStore` (existing) | Tenant context. Reused as-is. | Unchanged |
| `useUserPreferences` (existing) | Timezone, theme. Reused as-is. | Unchanged |

---

### 16.2 API Contract

#### 16.2.1 Existing Endpoints (S9.4) Consumed by DA UI

| Endpoint | Method | DA-UI Section | Notes |
|---|---|---|---|
| `POST /api/da/import` | POST | DA-UI-01 | Trigger import. Request body: `AuctionResultBatch` JSON. Response: `AuctionImportSession`. |
| `GET /api/da/import/{id}` | GET | DA-UI-01 | Poll import status. Response: `AuctionImportSession`. |
| `GET /api/da/alerts` | GET | DA-UI-06 | List alerts. Query params: `tenantId`, `status`, `severity`, `category`, `dateFrom`, `dateTo`, `offset`, `limit`. |
| `PUT /api/da/alerts/{id}/ack` | PUT | DA-UI-06 | Acknowledge alert. |

#### 16.2.2 New Endpoints Required

The S9.4 endpoint list covers commands but is missing several read endpoints needed by the UI. These are all **simulator-scope** (`pv-app` REST controllers delegating to Guice-managed services).

| Endpoint | Method | DA-UI Section | Response Type | Notes |
|---|---|---|---|---|
| `GET /api/da/import` | GET | DA-UI-01 | `List<AuctionImportSessionDto>` | Import history list. Query params: `tenantId`, `status?`, `zone?`, `dateFrom?`, `dateTo?`. Sorted by `importTimestamp DESC`. |
| `GET /api/da/settlement` | GET | DA-UI-02 | `DaSettlementGridDto` | Settlement grid for a delivery day. Query params: `tenantId`, `deliveryDay`, `zone`, `timezone?` (default `Europe/Berlin`). Returns interval rows + summary. |
| `GET /api/da/nominations` | GET | DA-UI-03 | `DaNominationGridDto` | Nomination comparison. Query params: `tenantId`, `deliveryDay`, `zone`, `balancingGroupId?`. Returns traded vs nominated per interval. |
| `GET /api/da/nominations/balancing-groups` | GET | DA-UI-03 | `List<BalancingGroupDto>` | Active balancing groups for dropdown. Query params: `tenantId`. |
| `GET /api/da/imbalance/daily` | GET | DA-UI-04 | `DaImbalanceDailyDto` | Daily imbalance detail. Query params: `tenantId`, `deliveryDay`, `balancingGroupId`. |
| `GET /api/da/imbalance/monthly` | GET | DA-UI-04 | `DaImbalanceMonthlyDto` | Monthly imbalance summary. Query params: `tenantId`, `yearMonth`, `balancingGroupId`. |
| `GET /api/da/fees` | GET | DA-UI-05 | `DaFeeBreakdownDto` | Fee breakdown for delivery day. Query params: `tenantId`, `deliveryDay`. |
| `GET /api/da/kpi` | GET | DA-UI-07 | `DaKpiDto` | KPI summary for delivery day + zone. Query params: `tenantId`, `deliveryDay`, `zone`. |
| `PUT /api/da/alerts/{id}/resolve` | PUT | DA-UI-06 | `OperationalAlertDto` | Resolve an acknowledged alert. |
| `GET /api/da/alerts/counts` | GET | DA-UI-06 | `Map<AlertCategory, Long>` | Badge counts per category. Query params: `tenantId`, `status?`. |

All `@RestController` classes live in `pv-app`. All `@Bean` methods delegate to `injector.getInstance(...)`. No `new` calls. D-13 compliant.

#### 16.2.3 Zod Schemas (additions to `pv-ui/src/schemas/api.ts`)

```
// DA Import Session
auctionImportSessionSchema = z.object({
  sessionId: z.string(),
  tenantId: z.string(),
  exchange: z.string(),
  biddingZone: z.string(),
  deliveryDay: z.string(),           // ISO local date YYYY-MM-DD
  importTimestamp: instantString,
  status: z.enum([
    'PENDING','VALIDATING','VALIDATED','IMPORTING','IMPORTED',
    'VALIDATION_FAILED','IMPORT_FAILED'
  ]),
  exchangeReportedTotalMwh: bigDecimalNullable,
  importedTotalMwh: bigDecimalNullable,
  intervalCount: z.number().int().nullable(),
  fileReference: z.string().nullable(),
  tradeIds: z.array(z.string()).nullable(),
  validationErrors: z.array(z.string()).nullable(),
  createdAt: instantString,
  completedAt: instantString.nullable(),
})

// DA Settlement Grid row
daSettlementRowSchema = z.object({
  intervalStart: instantString,
  intervalEnd: instantString,
  tradeId: z.string(),
  tradeLegId: z.string(),
  direction: z.enum(['BUY', 'SELL']),
  price: bigDecimalValue,
  volumeMw: bigDecimalValue,
  energyMwh: bigDecimalValue,
  amount: bigDecimalValue,
  cellStatus: z.string(),
  currency: z.string(),
})

// DA Settlement Grid response (wraps rows + summary)
daSettlementGridSchema = z.object({
  deliveryDay: z.string(),
  biddingZone: z.string(),
  intervalCount: z.number().int(),
  rows: z.array(daSettlementRowSchema),
  summary: z.object({
    totalEnergyMwh: bigDecimalValue,
    totalSettlement: bigDecimalValue,
    vwap: bigDecimalValue,
    currency: z.string(),
  }),
})

// Nomination comparison row
nominationComparisonRowSchema = z.object({
  intervalStart: instantString,
  intervalEnd: instantString,
  tradedMw: bigDecimalNullable,
  nominatedMw: bigDecimalNullable,
  deviationMw: bigDecimalNullable,
  status: z.enum(['OK', 'DEVIATION', 'MISSING']),
})

// Nomination grid response
daNominationGridSchema = z.object({
  deliveryDay: z.string(),
  balancingGroupId: z.string().nullable(),
  intervalCount: z.number().int(),
  rows: z.array(nominationComparisonRowSchema),
  summary: z.object({
    totalTradedMwh: bigDecimalValue,
    totalNominatedMwh: bigDecimalNullable,
    netDeviationMwh: bigDecimalNullable,
    intervalsWithDeviation: z.number().int(),
    totalIntervals: z.number().int(),
  }),
  nominationSubmitted: z.boolean(),
  gateClosureStatus: z.enum(['OK', 'WARNING', 'CRITICAL']).nullable(),
})

// Imbalance record row
imbalanceRowSchema = z.object({
  intervalStart: instantString,
  intervalEnd: instantString,
  nominatedMw: bigDecimalValue,
  actualMw: bigDecimalValue,
  imbalanceMw: bigDecimalValue,
  rebapPrice: bigDecimalValue,
  amount: bigDecimalValue,
  currency: z.string(),
})

// Daily imbalance response
daImbalanceDailySchema = z.object({
  deliveryDay: z.string(),
  balancingGroupId: z.string(),
  recordVersion: z.number().int(),
  rows: z.array(imbalanceRowSchema),
  summary: z.object({
    netImbalanceMwh: bigDecimalValue,
    netImbalanceCost: bigDecimalValue,
    maxIntervalImbalanceMw: bigDecimalValue,
    maxIntervalTime: instantString,
    currency: z.string(),
  }),
  hasPriorVersion: z.boolean(),
  correctionDate: instantString.nullable(),
})

// Monthly imbalance day summary
imbalanceDaySummarySchema = z.object({
  deliveryDay: z.string(),
  netImbalanceMwh: bigDecimalValue,
  netCost: bigDecimalValue,
  maxDeviationMw: bigDecimalValue,
  intervalsWithImbalance: z.number().int(),
  totalIntervals: z.number().int(),
  currency: z.string(),
})

// Monthly imbalance response
daImbalanceMonthlySchema = z.object({
  yearMonth: z.string(),
  balancingGroupId: z.string(),
  dailySummaries: z.array(imbalanceDaySummarySchema),
  monthlySummary: z.object({
    totalNetImbalanceMwh: bigDecimalValue,
    totalNetCost: bigDecimalValue,
    currency: z.string(),
  }),
})

// Fee breakdown
feeLineItemSchema = z.object({
  feeType: z.string(),
  ratePerMwh: bigDecimalValue,
  grossVolumeMwh: bigDecimalValue,
  feeAmount: bigDecimalValue,
  currency: z.string(),
})

daFeeBreakdownSchema = z.object({
  deliveryDay: z.string(),
  feeScheduleEffectiveDate: z.string(),
  memberTier: z.string().nullable(),
  items: z.array(feeLineItemSchema),
  totalFeeAmount: bigDecimalValue,
  grossVolumeMwh: bigDecimalValue,
  currency: z.string(),
})

// Operational alert
operationalAlertSchema = z.object({
  alertId: z.string(),
  tenantId: z.string(),
  category: z.enum([
    'AUCTION_INGESTION','DA_CLEARING_PRICES','NOMINATION_SCHEDULING',
    'IMBALANCE_SETTLEMENT','EXCHANGE_FEES','SETTLEMENT'
  ]),
  severity: z.enum(['CRITICAL', 'WARNING', 'INFO']),
  alertType: z.string(),
  message: z.string(),
  deliveryDay: z.string().nullable(),
  biddingZone: z.string().nullable(),
  sourceEventId: z.string().nullable(),
  raisedAt: instantString,
  acknowledgedBy: z.string().nullable(),
  acknowledgedAt: instantString.nullable(),
  resolvedAt: instantString.nullable(),
  status: z.enum(['OPEN', 'ACKNOWLEDGED', 'RESOLVED']),
})

// KPI summary
daKpiSchema = z.object({
  netVolumeMwh: bigDecimalValue,
  vwap: bigDecimalValue,
  settlementTotal: bigDecimalValue,
  exchangeFees: bigDecimalValue,
  imbalanceCost: bigDecimalNullable,
  openAlertCount: z.number().int(),
  maxAlertSeverity: z.enum(['CRITICAL', 'WARNING', 'INFO']).nullable(),
  currency: z.string(),
})

// Balancing group reference
balancingGroupSchema = z.object({
  bgId: z.string(),
  tsoArea: z.string(),
  bgCode: z.string(),
})
```

All schemas follow the existing pattern: `bigDecimalValue` for required decimals, `bigDecimalNullable` for optional, `instantString` for UTC timestamps. Inferred TypeScript types are exported via `z.infer<typeof ...>`.

---

### 16.3 New Components

#### 16.3.1 DaLayout (`pv-ui/src/components/da/DaLayout.tsx`)

**Purpose:** Parent layout for all DA routes. Renders the tab navigation (DA-UI-08) and the KPI strip (DA-UI-07).

**Props:** None (reads route context).

**Structure:**
- `DaNavTabs` -- horizontal tab group mapping to child routes (Import, Settlement, Nominations, Imbalance, Fees, Alerts). Active tab determined by current route path. Uses `role="tablist"` with `role="tab"` on each tab. Keyboard: arrow keys cycle tabs (roving tabindex, same pattern as `GranularityToggle`).
- `DaKpiStrip` -- renders 6 `KpiTile` instances (existing primitive, DA-UI-07). Data from `useDaKpi` hook. Each tile is wrapped in a link to the corresponding detail view. Color logic: `netVolumeMwh` green if positive (net BUY), red if negative (net SELL). `imbalanceCost` green if positive (receipt from TSO), red if negative (payment). `openAlertCount` uses red if `maxAlertSeverity === 'CRITICAL'`, amber if `WARNING`, neutral otherwise.
- `<Outlet />` -- TanStack Router outlet for child route content.

**Reused primitives:** `KpiTile`, `ErrorBoundary`.

#### 16.3.2 DaImportPage (`pv-ui/src/components/da/DaImportPage.tsx`)

**Purpose:** DA-UI-01 -- Auction import status panel and history.

**Children:**
- `DaImportHistoryTable` -- tabular list of all import sessions (DA-UI-01 Scenario 4). Columns: Delivery Day, Zone, Status, Trades, Total MWh, Imported At. Sortable by `importTimestamp` (default DESC). Filterable by status, zone, date range via a filter bar. Each row is clickable to select and show detail panel. Uses `@tanstack/react-table` for column definitions (same as `SettledDayGrid`).
- `DaImportDetailPanel` -- conditional panel showing selected session detail (Scenarios 2/3). Contains:
  - `ProgressStepper` (new primitive, S16.4.1) showing the status state machine.
  - Status badge via extended `StatusBadge` (see S16.4.5).
  - Numeric fields via `NumericCell` (existing): `exchangeReportedTotalMwh`, `importedTotalMwh`.
  - Volume match indicator: green if `exchangeReportedTotalMwh === importedTotalMwh`, red otherwise.
  - Validation errors list in a scrollable `<ul>` if status is `VALIDATION_FAILED`.
  - "View Trades" link navigating to `/da/settlement?deliveryDay=...&zone=...`.
  - "Retry Import" button (only on `VALIDATION_FAILED` / `IMPORT_FAILED`).

**Data:** `useDaImportHistory` (list), `useDaImportDetail` (single session, polled at 2s while non-terminal).

#### 16.3.3 DaSettlementPage (`pv-ui/src/components/da/DaSettlementPage.tsx`)

**Purpose:** DA-UI-02 -- Interval-level settlement grid.

**Children:**
- `DaSettlementFilterBar` -- delivery day picker (date input), zone dropdown, `SubGranularityToggle` (existing), `HideZeroToggle` (existing).
- `DaSettlementGrid` -- main grid. Column definitions:

| Column | Accessor | Cell Component | Precision | Width |
|---|---|---|---|---|
| Interval (CET) | computed from `intervalStart` | `formatIntervalTime()` local part | -- | 130 |
| Price (EUR/MWh) | `price` | `NumericCell` with negative price styling (S16.7.2) | `PRICE` | 120 |
| Volume (MW) | `volumeMw` | `NumericCell` | `MW` | 90 |
| Dir | `direction` | `StatusBadge` (extended, green BUY / red SELL) | -- | 60 |
| Energy (MWh) | `energyMwh` | `NumericCell` | `MWH` | 100 |
| Amount (EUR) | `amount` | `NumericCell` with credit/debit styling | `MONETARY` | 120 |
| Status | `cellStatus` | `StatusBadge` (extended) | -- | 80 |

- `DaSettlementSummaryRow` -- footer row with total energy, total settlement, VWAP. Uses `NumericCell`.
- **Negative price handling (DA-UI-02 Scenario 2):** The `Price` column applies `text-amber-500 dark:text-amber-400` class when price is negative. A tooltip reads "Negative clearing price -- buyer receives credit". The `Amount` column for BUY rows with negative price displays the value in green with "CR" suffix. This is handled by a custom cell renderer wrapping `NumericCell` with additional logic, not by modifying `NumericCell` itself.
- **Multiple trades per interval (DA-UI-02 Scenario 5):** Each trade-leg is a separate row. The `Interval (CET)` column uses `rowSpan` grouping when consecutive rows share the same `intervalStart` value.
- **DST rows:** Handled by `DstSeparatorRow` (S16.4.4). See S16.7 for rendering logic.

**Data:** `useDaSettlement` hook.

#### 16.3.4 DaNominationPage (`pv-ui/src/components/da/DaNominationPage.tsx`)

**Purpose:** DA-UI-03 -- Nomination workbench.

**Children:**
- `DaNominationFilterBar` -- delivery day picker, balancing group dropdown (populated by `useDaBalancingGroups`), "All BGs" option for aggregated view.
- `DaNominationGrid` -- comparison grid. Columns:

| Column | Accessor | Cell Component | Precision |
|---|---|---|---|
| Interval (CET) | computed | `formatIntervalTime()` | -- |
| Traded (MW) | `tradedMw` | `NumericCell` | `MW` |
| Nominated (MW) | `nominatedMw` | `NumericCell`, em-dash if null | `MW` |
| Deviation (MW) | `deviationMw` | `DeviationCell` (new primitive, S16.4.2) | `MW` |
| Status | `status` | text label: OK / DEVIATION / MISSING | -- |

- `DaNominationSummaryBar` -- total traded MWh, total nominated MWh, net deviation MWh, intervals-with-deviation count. Uses `NumericCell`.
- **Nomination not submitted banner (Scenario 2):** A `role="alert"` banner above the grid. Severity determines styling: INFO = blue, WARNING = amber, CRITICAL = red. Text includes gate closure countdown (computed client-side from `deliveryDay` and known gate closure time 14:30 CET D-1).

**Data:** `useDaNominations` hook, `useDaBalancingGroups` hook.

#### 16.3.5 DaImbalancePage (`pv-ui/src/components/da/DaImbalancePage.tsx`)

**Purpose:** DA-UI-04 -- Imbalance settlement view.

**Children:**
- View toggle: "Daily" / "Monthly" (uses `GranularityToggle` pattern with two options).
- `DaImbalanceDailyGrid` -- interval-level grid (Scenario 1). Columns: Interval, Nominated MW, Actual MW, Imbalance MW, reBAP price, Amount. Positive imbalance (over-delivery) amounts green, negative red. Uses `NumericCell` with `showSign`.
- `DaImbalanceMonthlyGrid` -- day-level aggregation (Scenario 2). Columns: Delivery Day, Net Imbalance MWh, Net Cost, Max Deviation MW, Intervals w/ Imbalance. Each row clickable to drill into daily view (sets filter and switches to daily view).
- `DaImbalanceSummaryBar` -- net imbalance energy, net cost, max interval imbalance.
- **TSO correction indicator (Scenario 3):** Banner with `role="status"` showing correction date and version. "Show original" toggle fetches `?recordVersion=1` and displays a delta column. Toggle is a simple boolean state.

**Data:** `useDaImbalanceDaily` hook, `useDaImbalanceMonthly` hook.

#### 16.3.6 DaFeesPage (`pv-ui/src/components/da/DaFeesPage.tsx`)

**Purpose:** DA-UI-05 -- Exchange fee summary.

**Children:**
- Delivery day picker.
- `DaFeeBreakdownTable` -- simple table with columns: Fee Type, Rate (EUR/MWh), Gross Volume (MWh), Fee Amount (EUR). Footer row with total. Uses `NumericCell`.
- Fee schedule metadata display: effective date, member tier.
- Explanatory note: "Fees computed on gross (absolute) volume: BUY X MWh + SELL Y MWh = Z MWh".

**Data:** `useDaFees` hook.

#### 16.3.7 DaAlertsPage (`pv-ui/src/components/da/DaAlertsPage.tsx`)

**Purpose:** DA-UI-06 -- Operational alerts dashboard.

**Children:**
- `CategoryBadgeStrip` (new primitive, S16.4.3) -- horizontal strip of category badges with counts. Clicking a badge filters the list. "All" badge resets filter.
- `DaAlertFilterBar` -- severity multi-select, date range picker, status filter (OPEN / ACKNOWLEDGED / RESOLVED). "Clear filters" link.
- `DaAlertList` -- list of `DaAlertRow` components. Each row shows: severity icon+color, category, message, delivery day, zone, timestamp, status. Clicking a row expands to show source event ID, full error details, and action buttons (Acknowledge / Resolve).
- Sorted by severity (CRITICAL first), then timestamp (newest first).

**Keyboard navigation (DA-UI-06 Scenario 4):**
- Arrow Up/Down: move focus between alert rows.
- Enter: expand/collapse focused row detail.
- A key: acknowledge focused alert (if OPEN). Guarded by `aria-keyshortcuts="a"` and confirmation.
- R key: resolve focused alert (if ACKNOWLEDGED). Guarded similarly.
- Escape: collapse detail / clear selection.
- Focus management: when an alert is acknowledged or resolved, focus stays on the same row. Screen reader announces status change via `aria-live="polite"` region.

**Data:** `useDaAlerts` hook (polling at 30s), `useDaAlertCounts` hook, `useDaAlertAcknowledge` mutation, `useDaAlertResolve` mutation.

---

### 16.4 New Primitives

#### 16.4.1 ProgressStepper (`pv-ui/src/components/primitives/ProgressStepper.tsx`)

**Purpose:** Multi-step progress indicator for the import status state machine (DA-UI-01).

**Props:**
```
interface ProgressStepperProps {
  steps: readonly string[];            // e.g. ['PENDING','VALIDATING','IMPORTING','IMPORTED']
  currentStep: string;                 // Current status value
  terminalStates?: readonly string[];  // e.g. ['IMPORTED','VALIDATION_FAILED','IMPORT_FAILED']
  errorStates?: readonly string[];     // e.g. ['VALIDATION_FAILED','IMPORT_FAILED']
  className?: string;
}
```

**Rendering:** Horizontal row of step circles connected by lines. Completed steps: filled circle with checkmark. Current step: pulsing ring animation (CSS `animate-pulse`). Error states: red circle with X icon. Future steps: hollow circle.

**Accessibility:** `role="progressbar"` with `aria-valuemin=0`, `aria-valuemax={steps.length}`, `aria-valuenow={currentIndex}`, `aria-valuetext={currentStep}`. Each step has `aria-label`.

#### 16.4.2 DeviationCell (`pv-ui/src/components/primitives/DeviationCell.tsx`)

**Purpose:** Numeric cell with threshold-based coloring for nomination deviations (DA-UI-03).

**Props:**
```
interface DeviationCellProps {
  value: string | number | null | undefined;
  precision: NumericPrecision;
  thresholds?: { minor: number; significant: number };  // defaults: minor=0.1, significant=5.0
  className?: string;
}
```

**Color logic:** Computes `abs(value)`:
- `=== 0`: no highlight, text "OK" (implicit via neutral color).
- `> 0 && <= minor`: neutral text (within tolerance).
- `> minor && <= significant`: `text-amber-500 dark:text-amber-400` (minor deviation).
- `> significant`: `text-red-600 dark:text-red-400` (significant deviation).

**Accessibility:** `aria-label` includes severity: "Deviation: negative 2.0 MW, minor". Color is supplemented by text indicator.

**Reuse:** Wraps `NumericCell` internally, adding the threshold color class.

#### 16.4.3 CategoryBadgeStrip (`pv-ui/src/components/primitives/CategoryBadgeStrip.tsx`)

**Purpose:** Horizontal row of category badges with counts for the alerts dashboard (DA-UI-06).

**Props:**
```
interface CategoryBadgeStripProps {
  categories: Array<{ key: string; label: string; count: number }>;
  activeCategory: string | null;       // null = "All"
  onCategoryClick: (key: string | null) => void;
  className?: string;
}
```

**Rendering:** Inline flex row. Each badge is a pill with label and count. Active badge has `bg-interactive-focus text-white`. Inactive badges have `bg-bg-secondary text-text-primary`. Zero-count badges are dimmed (`opacity-50`). An "All" badge is prepended with the total count.

**Accessibility:** `role="toolbar"` on the container. Each badge is a `<button>` with `aria-pressed={isActive}`. Keyboard: arrow keys cycle focus (roving tabindex), Enter/Space activates.

#### 16.4.4 DstSeparatorRow (`pv-ui/src/components/primitives/DstSeparatorRow.tsx`)

**Purpose:** Visual separator row indicating skipped or repeated hours on DST transition days (DA-UI-02 Scenarios 3/4).

**Props:**
```
interface DstSeparatorRowProps {
  type: 'spring-forward' | 'fall-back';
  columnCount: number;
}
```

**Rendering:**
- Spring-forward: full-width cell with text "DST spring-forward: 02:00-03:00 CET skipped" and a forward-skip icon. Background: `bg-amber-50 dark:bg-amber-900/20`.
- Fall-back: full-width cell with text "DST fall-back: 02:00-03:00 repeated" and a backward-skip icon. Same background.

**Accessibility:** `role="presentation"` (same as `DayBoundaryRow`). Not focusable via arrow-key grid navigation. Screen reader skips it during grid traversal but it is announced as part of the table structure.

**Relationship to existing `DayBoundaryRow`:** Similar pattern (non-data row, `role="presentation"`, `colSpan`), but distinct purpose. `DayBoundaryRow` separates calendar days in multi-day grids. `DstSeparatorRow` marks DST transitions within a single day. They may co-exist in the same grid if a multi-day view spans a DST transition day.

#### 16.4.5 StatusBadge Extension

The existing `StatusBadge` component (at `pv-ui/src/components/primitives/StatusBadge.tsx`) supports statuses: `SETTLED`, `TRANSITION`, `PARTIAL`, `FORWARD`, `TODAY`. DA requires additional status values.

**Design decision:** Rather than modifying `StatusBadge` (which would break the existing type contract), create a `DaStatusBadge` component in `pv-ui/src/components/da/DaStatusBadge.tsx` that wraps the same visual pattern but with a DA-specific `STATUS_CONFIG` map:

| Status | Label | Background | Icon |
|---|---|---|---|
| `IMPORTED` | Imported | green | checkmark |
| `IMPORTING` | Importing | blue | spinner |
| `VALIDATING` | Validating | blue | spinner |
| `PENDING` | Pending | gray | clock |
| `VALIDATION_FAILED` | Failed | red | X |
| `IMPORT_FAILED` | Failed | red | X |
| `BUY` | BUY | green | -- |
| `SELL` | SELL | red | -- |
| `CRITICAL` | CRIT | red | -- |
| `WARNING` | WARN | amber | -- |
| `INFO` | INFO | blue | -- |
| `OPEN` | Open | red | -- |
| `ACKNOWLEDGED` | Ack'd | amber | -- |
| `RESOLVED` | Resolved | green | checkmark |

**Why not extend the existing `StatusBadge`:** The existing component's `status` prop is a union type used for delivery status. Mixing delivery statuses with alert severities and import statuses would create an incoherent type. A separate component with its own status type is cleaner and avoids regressions in the existing dashboard.

---

### 16.5 Hooks and Data Fetching

All hooks follow the patterns established in `pv-ui/src/hooks/useDashboardQueries.ts`: TanStack Query `useQuery` / `useMutation` with Zod schema validation, tenant-aware query keys, and appropriate stale/refetch configuration.

#### 16.5.1 Query Key Factory (`pv-ui/src/api/daQueryKeys.ts`)

```
export const daKeys = {
  all: ['da'] as const,
  importHistory: (tenantId, filters) => [...daKeys.all, 'import-history', tenantId, filters],
  importDetail: (tenantId, sessionId) => [...daKeys.all, 'import-detail', tenantId, sessionId],
  settlement: (tenantId, deliveryDay, zone, timezone) =>
    [...daKeys.all, 'settlement', tenantId, deliveryDay, zone, timezone],
  nominations: (tenantId, deliveryDay, zone, bgId) =>
    [...daKeys.all, 'nominations', tenantId, deliveryDay, zone, bgId],
  balancingGroups: (tenantId) => [...daKeys.all, 'balancing-groups', tenantId],
  imbalanceDaily: (tenantId, deliveryDay, bgId) =>
    [...daKeys.all, 'imbalance-daily', tenantId, deliveryDay, bgId],
  imbalanceMonthly: (tenantId, yearMonth, bgId) =>
    [...daKeys.all, 'imbalance-monthly', tenantId, yearMonth, bgId],
  fees: (tenantId, deliveryDay) => [...daKeys.all, 'fees', tenantId, deliveryDay],
  kpi: (tenantId, deliveryDay, zone) => [...daKeys.all, 'kpi', tenantId, deliveryDay, zone],
  alerts: (tenantId, filters) => [...daKeys.all, 'alerts', tenantId, filters],
  alertCounts: (tenantId, status) => [...daKeys.all, 'alert-counts', tenantId, status],
}
```

Every key includes `tenantId` to prevent cross-tenant cache leaks (same invariant as `dashboardKeys`).

#### 16.5.2 Hook Definitions (`pv-ui/src/hooks/useDaQueries.ts`)

| Hook | Endpoint | staleTime | refetchInterval | Notes |
|---|---|---|---|---|
| `useDaImportHistory` | `GET /api/da/import` | 30s | 60s | Refetch on `DA_IMPORT_COMPLETED` SSE event |
| `useDaImportDetail` | `GET /api/da/import/{id}` | 0 | 2s (while non-terminal) | Polling stops when `status` is terminal. Uses `refetchInterval: (query) => isTerminal(query.state.data?.status) ? false : 2000` |
| `useDaSettlement` | `GET /api/da/settlement` | 120s | -- | Settled data is stable. Refetch on `SETTLEMENT_COMPUTED` SSE event for the matching delivery day. |
| `useDaNominations` | `GET /api/da/nominations` | 60s | 120s | Moderate polling; nominations can be updated pre-delivery. |
| `useDaBalancingGroups` | `GET /api/da/nominations/balancing-groups` | 300s | -- | Reference data, rarely changes. |
| `useDaImbalanceDaily` | `GET /api/da/imbalance/daily` | 120s | -- | Stable after TSO publication. |
| `useDaImbalanceMonthly` | `GET /api/da/imbalance/monthly` | 120s | -- | Aggregated view, stable. |
| `useDaFees` | `GET /api/da/fees` | 300s | -- | Stable once computed. |
| `useDaKpi` | `GET /api/da/kpi` | 30s | 60s | Refreshed frequently for live dashboard feel. |
| `useDaAlerts` | `GET /api/da/alerts` | 10s | 30s | Fast refresh for alert awareness. |
| `useDaAlertCounts` | `GET /api/da/alerts/counts` | 10s | 30s | Drives badge counts on `CategoryBadgeStrip`. |

#### 16.5.3 Mutations (`pv-ui/src/hooks/useDaMutations.ts`)

| Hook | Endpoint | On Success |
|---|---|---|
| `useDaImportTrigger` | `POST /api/da/import` | Invalidate `daKeys.importHistory`. Navigate to import detail view. |
| `useDaAlertAcknowledge` | `PUT /api/da/alerts/{id}/ack` | Invalidate `daKeys.alerts` and `daKeys.alertCounts`. |
| `useDaAlertResolve` | `PUT /api/da/alerts/{id}/resolve` | Invalidate `daKeys.alerts` and `daKeys.alertCounts`. |

All mutations use `useMutation` from TanStack Query. Error handling follows the existing `ApiError` pattern from `pv-ui/src/api/client.ts`.

#### 16.5.4 SSE Invalidation Extension

The existing `useRealtimeInvalidation` hook at `pv-ui/src/hooks/useRealtimeInvalidation.ts` must be extended to handle DA-specific change types. The `ChangeType` union and `INVALIDATION_MAP` are amended:

```
// Additional ChangeType values:
| 'DA_IMPORT_COMPLETED'
| 'DA_ALERT_RAISED'
| 'DA_SETTLEMENT_UPDATED'

// Additional INVALIDATION_MAP entries:
DA_IMPORT_COMPLETED: ['da', 'import-history', 'import-detail', 'kpi'],
DA_ALERT_RAISED: ['da', 'alerts', 'alert-counts', 'kpi'],
DA_SETTLEMENT_UPDATED: ['da', 'settlement', 'kpi'],
```

The invalidation targets use the `daKeys.all` prefix (`'da'`), so `queryClient.invalidateQueries({ queryKey: ['da', 'alerts'] })` correctly invalidates all alert queries across filter permutations.

**Backend SSE source:** The existing `/api/dashboard/events` SSE endpoint in `pv-app` must emit these new change types. The endpoint's Kafka consumer subscribes to the additional topics: `posval.AuctionImportCompleted`, `posval.OperationalAlertRaised`, `posval.SettlementComputed` (already subscribed). This is a `pv-app`-only change (simulator-scope).

---

### 16.6 Routing

#### 16.6.1 URL Structure

| Path | Component | DA-UI Section |
|---|---|---|
| `/da` | `DaLayout` (redirect to `/da/import`) | DA-UI-08 |
| `/da/import` | `DaImportPage` | DA-UI-01 |
| `/da/import/:sessionId` | `DaImportPage` (with detail panel open) | DA-UI-01 |
| `/da/settlement` | `DaSettlementPage` | DA-UI-02 |
| `/da/nominations` | `DaNominationPage` | DA-UI-03 |
| `/da/imbalance` | `DaImbalancePage` | DA-UI-04 |
| `/da/fees` | `DaFeesPage` | DA-UI-05 |
| `/da/alerts` | `DaAlertsPage` | DA-UI-06 |

#### 16.6.2 Search Params (Filter Persistence)

All filter state is persisted in URL search params for deep-linkability (DA-UI-08 Scenario 1):

| Param | Pages | Example |
|---|---|---|
| `deliveryDay` | settlement, nominations, imbalance, fees | `2026-09-16` |
| `zone` | settlement, nominations | `DE_LU` |
| `bg` | nominations, imbalance | `BG-DE-001` |
| `yearMonth` | imbalance (monthly view) | `2026-09` |
| `status` | import, alerts | `OPEN` |
| `severity` | alerts | `CRITICAL,WARNING` |
| `view` | imbalance | `daily` or `monthly` |

#### 16.6.3 Route Tree Amendment (`pv-ui/src/routeTree.tsx`)

The existing `routeTree.tsx` defines `rootRoute` -> `indexRoute` + `dashboardRoute`. The DA routes are added as siblings:

```
rootRoute
  +-- indexRoute (/)
  +-- dashboardRoute (/dashboard/$portfolioId)
  +-- daLayoutRoute (/da)
       +-- daImportRoute (/da/import)
       +-- daImportDetailRoute (/da/import/$sessionId)
       +-- daSettlementRoute (/da/settlement)
       +-- daNominationRoute (/da/nominations)
       +-- daImbalanceRoute (/da/imbalance)
       +-- daFeesRoute (/da/fees)
       +-- daAlertsRoute (/da/alerts)
```

The `daLayoutRoute` uses `DaLayout` as its component, providing the tab navigation and KPI strip to all child routes via `<Outlet />`.

#### 16.6.4 Cross-Navigation from Existing Dashboard (DA-UI-08 Scenario 2)

When a user drills from the existing rollup grid (L2) into the position ledger (L3) and selects a DA trade (`originType = "EXCHANGE_FILL"`, `instrumentType = "DA_EXCHANGE_SPOT"`), a "View DA Settlement" action link is rendered. This link navigates to `/da/settlement?deliveryDay=YYYY-MM-DD&zone=ZONE`.

Implementation: The `PositionLedger` component checks `originType` on the selected row. If `"EXCHANGE_FILL"`, it renders an additional action button. The button constructs the navigation URL from the position's `deliveryStart` (converted to local date in the user's timezone) and `deliveryPointId` (mapped to zone).

---

### 16.7 DST Display Logic

#### 16.7.1 Interval Count Awareness

The settlement grid response includes `intervalCount` (92, 96, or 100). The UI uses the existing `getDstInfo()` utility from `pv-ui/src/lib/dateUtils.ts` to determine DST status and display the appropriate banner.

#### 16.7.2 Spring-Forward Gap (DA-UI-02 Scenario 3)

On a spring-forward day (92 intervals), the API returns intervals in UTC order. The UI:

1. Formats each interval's `intervalStart` using `formatIntervalTime()` which includes the timezone abbreviation (CET or CEST).
2. Detects the gap: after the interval ending at `01:45 CET` (UTC `00:45`), the next interval starts at `03:00 CEST` (UTC `01:00`). There is no `02:xx CET` interval.
3. Inserts a `DstSeparatorRow` with `type="spring-forward"` between these two rows.
4. Detection algorithm: iterate through sorted rows. If the local hour jumps from 01:xx to 03:xx (skipping 02:xx), insert the separator. The jump is detected by comparing `formatIntervalTime().local` values.

#### 16.7.3 Fall-Back Duplicate Hour (DA-UI-02 Scenario 4)

On a fall-back day (100 intervals), the duplicate hour 02:00-03:00 appears twice. The API returns all 100 intervals in UTC order. The UI:

1. Formats using `formatIntervalTime()`. The first occurrence shows `02:00 CEST`, the second shows `02:00 CET`. The `Intl.DateTimeFormat` with `timeZoneName: 'short'` correctly distinguishes them because the underlying UTC instants differ.
2. Inserts a `DstSeparatorRow` with `type="fall-back"` between the last `02:xx CEST` row and the first `02:xx CET` row.
3. Detection algorithm: iterate through sorted rows. If the timezone abbreviation changes from `CEST` to `CET` within the `02:xx` hour, insert the separator.

#### 16.7.4 Day Summary

The KPI tile for "Total Hours" and the grid summary row use `intervalCount / 4` to compute the hour count (23, 24, or 25). The `intervalCount` comes from the API response, not from client-side computation, ensuring server-side authority (FR-024/FR-025, S10c.2).

---

### 16.8 Accessibility Implementation (DA-UI-09)

#### 16.8.1 Grid Navigation

All DA grids (`DaSettlementGrid`, `DaNominationGrid`, `DaImbalanceDailyGrid`, `DaImbalanceMonthlyGrid`) use `role="grid"` on the `<table>` element with:

- `aria-rowcount={totalRows}` (includes header row).
- `aria-colcount={columns.length}`.
- `aria-label` descriptive of the grid content (e.g., "DA settlement intervals for 16 September 2026, DE_LU").

Row cells use `role="gridcell"`. Header cells use `role="columnheader"`.

**Keyboard:** Same pattern as existing `SettledDayGrid`:
- Arrow keys move between cells.
- Enter activates/drills (e.g., clicking a monthly imbalance row to drill to daily).
- Space toggles selection (where applicable).
- Escape closes detail panels.
- Tab order follows logical reading order (filter bar -> grid -> summary -> next section).

#### 16.8.2 Screen Reader Announcements

- **Alert severity:** Announced on focus via `aria-label`: "Critical alert: Import validation failed, volume mismatch minus 10 MWh, 16 September 2026, DE_LU, Open".
- **Status transitions:** When an import moves from IMPORTING to IMPORTED, an `aria-live="polite"` region announces "Import completed: 96 trades imported for 16 September 2026, DE_LU".
- **Deviation cells:** `aria-label` includes magnitude: "Deviation: minus 2.0 MW, minor deviation".
- **DST separator rows:** Not announced during grid navigation (`role="presentation"`), but the grid's `aria-label` includes "23-hour day" or "25-hour day" when DST applies.

#### 16.8.3 Color Independence

Every color-coded indicator has a text or icon alternative:

| Indicator | Color | Text/Icon Alternative |
|---|---|---|
| Positive amount (credit) | Green | "CR" suffix |
| Negative amount (debit) | Red | Minus sign prefix |
| Negative price | Amber | "(neg)" text label in tooltip |
| BUY direction | Green | "BUY" text label |
| SELL direction | Red | "SELL" text label |
| CRITICAL severity | Red | "CRIT" text label |
| WARNING severity | Amber | "WARN" text label |
| Deviation minor | Amber | Deviation magnitude shown |
| Deviation significant | Red | Deviation magnitude shown |
| Import success | Green | "IMPORTED" text + checkmark icon |
| Import failure | Red | "FAILED" text + X icon |

#### 16.8.4 Focus Management

- **Panel transitions:** When navigating from import history to import detail, focus moves to the detail panel heading (`<h3>`). When the detail panel closes, focus returns to the row that opened it (stored in a `useRef`).
- **Alert acknowledge/resolve:** After action, focus stays on the same alert row. The row updates in place.
- **Tab navigation between sections:** The DA tab group uses `role="tablist"` / `role="tab"` / `role="tabpanel"`. `aria-selected` marks the active tab. Keyboard: arrow keys move between tabs, Enter/Space activates. The associated `role="tabpanel"` receives focus on tab activation.
- **Modal-like behaviors:** The import detail panel and alert expansion are inline expansions, not modals. No focus trap. Escape collapses.

#### 16.8.5 Keyboard Shortcuts

A help tooltip accessible via the `?` key documents all shortcuts. The tooltip is an `aria-describedby` region.

| Shortcut | Context | Action |
|---|---|---|
| Arrow keys | Grid | Navigate rows/cells |
| Enter | Grid row | Expand/drill |
| Escape | Any panel | Collapse/close |
| A | Alert row (OPEN) | Acknowledge |
| R | Alert row (ACK) | Resolve |
| ? | Global | Show shortcut help |

Shortcuts A and R are only active when the alert list has keyboard focus, preventing conflicts with text input fields.

---

### 16.9 File Layout

All new files follow existing directory conventions:

```
pv-ui/src/
  api/
    daQueryKeys.ts          (S16.5.1)
    daApi.ts                (fetch functions, same pattern as dashboard.ts)
  components/
    da/
      DaLayout.tsx          (S16.3.1)
      DaNavTabs.tsx
      DaKpiStrip.tsx
      DaImportPage.tsx      (S16.3.2)
      DaImportHistoryTable.tsx
      DaImportDetailPanel.tsx
      DaSettlementPage.tsx  (S16.3.3)
      DaSettlementGrid.tsx
      DaSettlementSummaryRow.tsx
      DaNominationPage.tsx  (S16.3.4)
      DaNominationGrid.tsx
      DaNominationSummaryBar.tsx
      DaImbalancePage.tsx   (S16.3.5)
      DaImbalanceDailyGrid.tsx
      DaImbalanceMonthlyGrid.tsx
      DaImbalanceSummaryBar.tsx
      DaFeesPage.tsx        (S16.3.6)
      DaFeeBreakdownTable.tsx
      DaAlertsPage.tsx      (S16.3.7)
      DaAlertFilterBar.tsx
      DaAlertList.tsx
      DaAlertRow.tsx
      DaStatusBadge.tsx     (S16.4.5)
    primitives/
      ProgressStepper.tsx   (S16.4.1)
      DeviationCell.tsx     (S16.4.2)
      CategoryBadgeStrip.tsx (S16.4.3)
      DstSeparatorRow.tsx   (S16.4.4)
  hooks/
    useDaQueries.ts         (S16.5.2)
    useDaMutations.ts       (S16.5.3)
    useDaFilters.ts         (S16.1.3)
    useDaSelection.ts       (S16.1.3)
  schemas/
    daApi.ts                (S16.2.3, DA-specific Zod schemas)
```

### 16.10 Testing Strategy (UI)

#### 16.10.1 Component Tests

Each new component gets a co-located `*.test.tsx` file using Vitest + React Testing Library.

| Test file | What it tests |
|---|---|
| `ProgressStepper.test.tsx` | Renders correct step states (completed, current, future, error). ARIA progressbar attributes. |
| `DeviationCell.test.tsx` | Threshold color classes for 0, minor, significant values. ARIA label includes severity. |
| `CategoryBadgeStrip.test.tsx` | Renders badge counts. Active badge styling. Keyboard navigation. |
| `DstSeparatorRow.test.tsx` | Correct text for spring-forward and fall-back. `role="presentation"`. |
| `DaStatusBadge.test.tsx` | All status variants render correct label, icon, and color. |
| `DaSettlementGrid.test.tsx` | Renders 96 rows for normal day. Renders 92 rows + DST separator for spring-forward. Renders 100 rows + DST separator for fall-back. Negative price styling. Multiple trades per interval grouping. |
| `DaAlertList.test.tsx` | Keyboard navigation (arrow keys, Enter, Escape). A/R shortcut keys. ARIA announcements on acknowledge. |
| `DaNominationGrid.test.tsx` | Deviation coloring thresholds. Missing nomination banner with severity. |
| `DaImbalanceDailyGrid.test.tsx` | Over-delivery green, under-delivery red. Sign display. |

#### 16.10.2 Hook Tests

| Test file | What it tests |
|---|---|
| `useDaQueries.test.ts` | Query key structure includes tenantId. Import detail polling stops on terminal state. |
| `useDaMutations.test.ts` | Cache invalidation on success. Error handling. |

#### 16.10.3 Integration Considerations

The UI tests mock the API layer (MSW or manual fetch mocks). They do NOT test against real backend endpoints. Backend API contract verification is the responsibility of the backend integration tests (S12). If API contract drift occurs, the Zod schema validation in the fetch functions will throw a parse error, surfacing the mismatch immediately at runtime.

---

### 16.11 Open Items (UI-Specific)

| # | Item | Blocking? | Owner |
|---|---|---|---|
| UI-OI-1 | **Import trigger UI.** DA-UI-01 shows import progress, but the functional spec does not specify a file upload UI for triggering imports. Is the import triggered via file upload in the UI, via a separate SFTP/API integration, or via a CLI command? The current S9.4 endpoint accepts a POST with the parsed batch as JSON body. A file upload UI would require a parser endpoint. | **NO** for v1 (assume import is triggered via REST/CLI, UI only shows status) | Product |
| UI-OI-2 | **Imbalance "Show original" toggle endpoint.** DA-UI-04 Scenario 3 requires fetching prior-version imbalance data. The `GET /api/da/imbalance/daily` endpoint needs a `recordVersion` query parameter, or a separate endpoint for version comparison. Not yet defined in S9.4. | **YES** for Scenario 3 | Solutions Architect |
| UI-OI-3 | **Navigation from existing dashboard.** DA-UI-08 Scenario 2 requires the `PositionLedger` component to detect DA trades and show a "View DA Settlement" link. This requires `instrumentType` or `originType` to be present in the `PositionContributionDto` response. The current schema (`positionContributionSchema`) does not include `instrumentType`. An additional field must be added to the DTO. | **YES** for cross-navigation | Implementation team |
| UI-OI-4 | **Gate closure countdown computation.** DA-UI-03 Scenario 2 computes gate closure proximity client-side. The nomination gate closure time (14:30 CET) is a business rule. Should this be returned by the API (in the nomination grid response as a field), or hardcoded in the UI? Hardcoding is fragile if gate closure rules differ by zone. | **SHOULD-FIX** before v1 | Functional Expert |
| UI-OI-5 | **Main navigation update.** DA-UI-08 requires a "Day-Ahead" entry in the main navigation (sidebar or top nav). The existing `AppShell` component needs amendment. The navigation structure (sidebar vs top tabs) is not specified in the functional spec. | **NO** (implementation detail) | Implementation team |

---

*Hand-off: This specification is ready for review by functional-expert (to validate domain assumptions A-1 through A-8 and resolve OI-1 through OI-12 plus UI-OI-1 through UI-OI-5) and then for implementation by implementation-engineer (backend starting with Phase 1, UI starting with Phase 7).*
