# Technical Specification — Volume Series Import v1.0

## §1 — Metadata & Status

| Field | Value |
|-------|-------|
| **Status** | Draft |
| **Date** | 2026-08-02 |
| **Deciders** | Architecture team |
| **Extends** | S3 (VolumeSeries), `TECH-SPEC-position-valuation-library_guice-v1.0` |
| **Companion Specs** | `VOLUME_SERIES_SPEC-V3_0.md`, `VOLUME_SERIES_DATA_ARCHITECTURE-V2_0.md`, `functional-spec-position-valuation-v1.0.md` |

---

## §2 — Scope & Requirements

### 2.1 Problem Statement

Volume series intervals for PROFILE series are created programmatically via `EagerStrategy` during trade capture. FORECAST and METERED_ACTUAL intervals, however, originate from external sources — weather forecast services, metering systems, TSO data feeds, or manual spreadsheets. **Import is the primary ingestion path for FORECAST and METERED_ACTUAL series types.** For PPA assets with multi-year delivery windows (5+ years at 15-min granularity = ~175,200 intervals), bulk-import capability is essential.

### 2.2 In Scope

1. **CSV import** — two-file approach: series metadata CSV + intervals CSV, linked by `series_key`
2. **Excel import** — single `.xlsx` workbook with two sheets (Series Metadata, Intervals)
3. **Programmatic API** — `VolumeSeriesImporter` port interface for system-to-system integration (weather feeds, metering APIs)
4. **Auto-supersession** — if a series with the same `series_key` exists, automatically supersede (mark old SUPERSEDED, create new version)
5. **Validation** — series-level, interval-level, and cross-validation rules with line-numbered error reporting
6. **Batch persistence** — via `BatchWriter` (Pattern #20, TR-017)
7. **Event publishing** — `VolumePublished` / `VolumeSuperseded` via outbox (Pattern #24)
8. **All series types** — FORECAST, PROFILE, and METERED_ACTUAL

### 2.3 Out of Scope

| Concern | Rationale |
|---------|-----------|
| REST controller / HTTP endpoint | Handled by API layer (future spec) |
| UI/UX for file upload | Handled by frontend spec (future) |
| Scheduled automatic imports | Handled by scheduling infrastructure |
| File storage / upload infrastructure | Handled by infrastructure layer |
| DDL changes | No schema changes needed; uses existing `volume_series` / `volume_interval` tables |

### 2.4 Functional Requirements

| ID | Requirement |
|----|-------------|
| FR-IMP-001 | The system shall accept volume series data via CSV (two-file), Excel (.xlsx), and programmatic API |
| FR-IMP-002 | Series metadata and interval data are linked by `series_key` — the stable external identifier |
| FR-IMP-003 | If a series with the same `(tenantId, series_key)` already exists in state CURRENT or EFFECTIVE, the import shall auto-supersede: mark the existing version SUPERSEDED/AMENDED and create a new version with incremented `version_id` |
| FR-IMP-004 | If a series with the same key exists but with a different `series_type`, the import shall reject that series with an error |
| FR-IMP-005 | If `energy` is absent or null in the interval data, the system shall compute it from `volume` using `VolumeUnit.toEnergy(volume, duration, NumericPrecision)` |
| FR-IMP-006 | Each series in an import batch is a separate unit-of-work. Failure of one series shall not abort other series in the batch |
| FR-IMP-007 | The import shall return a structured `ImportResult` with counts and per-line errors |
| FR-IMP-008 | Interval persistence shall use `BatchWriter` with configurable `pv.batch.size` (Pattern #20) |
| FR-IMP-009 | After successful persistence, the system shall publish `VolumePublished` (new series) or `VolumeSuperseded` (replaced series) events within the same transaction (Pattern #24) |
| FR-IMP-010 | Imports shall be idempotent: re-importing the same `(tenantId, series_key, version_id)` tuple shall be a no-op (Pattern #28) |
| FR-IMP-011 | The system shall validate all intervals fall within the series `[delivery_start, delivery_end)` half-open range |
| FR-IMP-012 | The system shall reject overlapping intervals within the same series |
| FR-IMP-013 | The system shall validate FORECAST series require `asset_id` and PROFILE series require `trade_leg_id` (D-11) |
| FR-IMP-014 | The import shall support all `TimeGranularity` values: MIN_5, MIN_15, MIN_30, HOURLY, DAILY, MONTHLY |
| FR-IMP-015 | Target performance: 175,200 intervals (5-year @ 15-min) imported in < 30 seconds per series |
| FR-IMP-016 | Memory constraint: the importer shall not load the entire file into memory; intervals are grouped per series and flushed incrementally |

---

## §3 — Import Data Model

### 3.1 Two-File Approach

Import data is structured as two logical tables linked by `series_key`:

```
┌──────────────────────┐       ┌──────────────────────┐
│  Series Metadata     │  1:N  │  Intervals           │
│  (one row per series)│──────>│  (many rows per key)  │
│  PK: series_key      │       │  FK: series_key       │
└──────────────────────┘       └──────────────────────┘
```

- **CSV:** Two separate `.csv` files — `series_metadata.csv` and `intervals.csv`
- **Excel:** One `.xlsx` workbook — Sheet 1 (`SeriesMetadata`), Sheet 2 (`Intervals`)
- **Programmatic API:** `List<SeriesImportRequest>` — each request contains header + intervals inline

### 3.2 Series Metadata Columns

| Column | Type | Required | Default | Notes |
|--------|------|----------|---------|-------|
| `series_key` | String | Yes | — | Stable identifier, max 128 chars. E.g., `FCST-WP-NORDSEE`, `VS-T5500-1`, `MTR-WP-NORDSEE` |
| `series_type` | Enum | Yes | — | `FORECAST`, `PROFILE`, or `METERED_ACTUAL` |
| `asset_id` | String | Conditional | `null` | **Required if** `series_type = FORECAST` or `METERED_ACTUAL`. Must be null for PROFILE (unless asset-linked PPA). |
| `trade_leg_id` | String | Conditional | `null` | **Required if** `series_type = PROFILE`. Must be null for FORECAST. |
| `volume_unit` | Enum | Yes | — | `MW_CAPACITY` or `MWH_PER_PERIOD` |
| `time_granularity` | Enum | Yes | — | `MIN_5`, `MIN_15`, `MIN_30`, `HOURLY`, `DAILY`, `MONTHLY` |
| `delivery_start` | ISO-8601 | Yes | — | Start of delivery window (inclusive). Format: `2025-01-01T00:00:00+01:00[Europe/Berlin]` |
| `delivery_end` | ISO-8601 | Yes | — | End of delivery window (exclusive). Must be > `delivery_start`. |
| `delivery_timezone` | ZoneId | Yes | — | E.g., `Europe/Berlin`, `UTC`. Used for interval-to-delivery period containment checks. |
| `quality_state` | Enum | No | Series-type default | Default: `CURRENT` (FORECAST), `EFFECTIVE` (PROFILE), `PROVISIONAL` (METERED_ACTUAL) |
| `version_id` | long | No | Auto | If absent: `1` for new series, `existing.versionId + 1` for supersession |
| `valid_time` | ISO-8601 | No | `null` | Only relevant for PROFILE (REMIT, FR-009g). Nullable for FORECAST/METERED_ACTUAL. |

### 3.3 Interval Columns

| Column | Type | Required | Default | Notes |
|--------|------|----------|---------|-------|
| `series_key` | String | Yes | — | Foreign key to series metadata. Must match a row in the series file. |
| `interval_start` | ISO-8601 | Yes | — | Start of interval (inclusive). Format: `2025-01-01T00:00:00Z` (UTC Instant) |
| `interval_end` | ISO-8601 | Yes | — | End of interval (exclusive). Must be > `interval_start`. |
| `volume` | BigDecimal | Yes | — | Volume value in the series unit. Precision ≤ 15, scale ≤ 8. |
| `energy` | BigDecimal | No | Computed | If absent: computed via `VolumeUnit.toEnergy(volume, duration, NumericPrecision)`. If present: stored as-is (no validation against computed value). |

### 3.4 Timestamp Conventions

- **Series metadata** timestamps (`delivery_start`, `delivery_end`): zoned datetimes in market-local time with timezone suffix. These map to `ZonedDateTime` and define the delivery period.
- **Interval** timestamps (`interval_start`, `interval_end`): UTC instants. These map to `Instant` and represent the physical time boundaries.
- This matches the existing domain model: `DeliveryPeriod` uses `ZonedDateTime`, `VolumeInterval` uses `Instant`.

---

## §4 — Domain Port: `VolumeSeriesImporter`

> **TR-IMP-001** — `VolumeSeriesImporter` is a hexagonal port (Pattern #18) in `pv-domain/port/ingest/`. It declares import capabilities without coupling to file format or persistence mechanism. Adapters for CSV, Excel, and future formats implement parsing; the port's default methods orchestrate validation, persistence, and event publishing.

### 4.1 Port Interface

```java
package com.power.posval.domain.port.ingest;

/**
 * Port interface for volume series import. Extends S3.
 * Supports CSV, Excel, and programmatic ingestion.
 * FR-IMP-001, Pattern #18.
 */
public interface VolumeSeriesImporter {

    /**
     * Import from two CSV streams: series metadata + intervals.
     * FR-IMP-001, FR-IMP-002.
     */
    ImportResult importFromCsv(InputStream seriesMetadata,
                                InputStream intervals,
                                String tenantId);

    /**
     * Import from Excel workbook (.xlsx): Sheet 1 = series, Sheet 2 = intervals.
     * FR-IMP-001.
     */
    ImportResult importFromExcel(InputStream workbook, String tenantId);

    /**
     * Programmatic API for system-to-system integration.
     * FR-IMP-001.
     */
    ImportResult importSeries(List<SeriesImportRequest> requests, String tenantId);
}
```

### 4.2 Request & Result Records

```java
/** A single series to import with its intervals. */
record SeriesImportRequest(
    String seriesKey,
    SeriesType seriesType,
    String assetId,              // nullable
    String tradeLegId,           // nullable
    VolumeUnit volumeUnit,
    TimeGranularity granularity,
    DeliveryPeriod deliveryPeriod,
    QualityState qualityState,   // nullable → uses type-default
    Long versionId,              // nullable → auto-assigned
    Instant validTime,           // nullable
    List<IntervalRow> intervals
) {}

/** A single interval row from import data. */
record IntervalRow(
    Instant start,
    Instant end,
    BigDecimal volume,
    BigDecimal energy            // nullable → computed from volume + duration
) {}

/** Result of an import operation. */
record ImportResult(
    int seriesCreated,
    int seriesSuperseded,
    int totalIntervalsImported,
    List<ImportError> errors
) {
    public boolean hasErrors() { return !errors.isEmpty(); }
    public boolean isFullSuccess() { return errors.isEmpty(); }
}

/** A single validation or processing error. */
record ImportError(
    int lineNumber,              // 0 for programmatic API, 1-based for CSV/Excel
    String seriesKey,
    String field,                // nullable — which column/field failed
    String message
) {}
```

---

## §5 — Domain Service: `DefaultVolumeSeriesImporter`

> **TR-IMP-002** — The import service follows a three-phase pipeline: **parse → validate → persist**. Each series is processed independently (FR-IMP-006). Parsing is delegated to format-specific adapters (Pattern #9: Strategy). Validation and persistence are shared across all formats.

### 5.1 Architecture

```
                     ┌───────────────────┐
                     │  VolumeSeriesImporter (port)  │
                     └──────────┬────────┘
                                │
                     ┌──────────▼────────┐
                     │  DefaultVolumeSeriesImporter  │
                     │  (domain service)             │
                     └──────────┬────────┘
                                │
              ┌─────────────────┼─────────────────┐
              │                 │                 │
    ┌─────────▼──────┐ ┌───────▼───────┐ ┌───────▼───────┐
    │  CsvImportParser│ │ExcelImportParser│ │ (Programmatic) │
    │  (adapter)      │ │  (adapter)      │ │ (direct call)  │
    └────────────────┘ └────────────────┘ └────────────────┘
```

### 5.2 Processing Pipeline

```java
public class DefaultVolumeSeriesImporter implements VolumeSeriesImporter {

    private final VolumeSeriesRepository seriesRepo;
    private final DomainEventPublisher eventPublisher;
    private final NumericPrecision np;
    private final CsvImportParser csvParser;
    private final ExcelImportParser excelParser;

    // @Inject constructor ...

    @Override
    public ImportResult importSeries(List<SeriesImportRequest> requests,
                                      String tenantId) {
        int created = 0, superseded = 0, intervals = 0;
        List<ImportError> errors = new ArrayList<>();

        for (SeriesImportRequest req : requests) {
            try {
                // Phase 1: Validate
                List<ImportError> validationErrors = validate(req);
                if (!validationErrors.isEmpty()) {
                    errors.addAll(validationErrors);
                    continue;
                }

                // Phase 2: Compute energy where absent
                List<VolumeInterval> domainIntervals = buildIntervals(req);

                // Phase 3: Check for existing series → supersede or create
                Optional<VolumeSeries> existing =
                    seriesRepo.findCurrentBySeriesKey(tenantId, req.seriesKey());

                if (existing.isPresent()) {
                    // Auto-supersede (FR-IMP-003)
                    VolumeSeries newSeries = buildSeries(req, tenantId,
                        existing.get().versionId() + 1, domainIntervals);
                    seriesRepo.supersede(existing.get(), newSeries);
                    publishSupersededEvent(existing.get(), newSeries);
                    superseded++;
                } else {
                    // Create new (version_id = 1 or as specified)
                    long vid = req.versionId() != null ? req.versionId() : 1L;
                    VolumeSeries newSeries = buildSeries(req, tenantId,
                        vid, domainIntervals);
                    seriesRepo.save(newSeries);
                    publishCreatedEvent(newSeries);
                    created++;
                }
                intervals += domainIntervals.size();

            } catch (Exception e) {
                errors.add(new ImportError(0, req.seriesKey(), null,
                    "Unexpected error: " + e.getMessage()));
            }
        }

        return new ImportResult(created, superseded, intervals, errors);
    }
}
```

### 5.3 Energy Computation

When `energy` is absent in the interval data (FR-IMP-005):

```java
private BigDecimal computeEnergy(VolumeUnit unit, BigDecimal volume,
                                  Instant start, Instant end) {
    Duration elapsed = Duration.between(start, end);
    return unit.toEnergy(volume, elapsed, np);
}
```

Uses `VolumeUnit.toEnergy()` from `pv-domain/model/VolumeUnit.java`:
- `MW_CAPACITY`: `energy = volume × (elapsed_seconds / 3600)`, rounded to `NumericPrecision.Domain.ENERGY` scale
- `MWH_PER_PERIOD`: `energy = volume` (pass-through)

### 5.4 Transaction Boundary

Each series is wrapped in its own `UnitOfWork` (from `pv-persistence/batch/UnitOfWork.java`):

```java
unitOfWork.run(em -> {
    seriesRepo.save(newSeries);       // header + BatchWriter for intervals
    eventPublisher.publish(event);    // outbox in same transaction (Pattern #24)
});
```

This ensures atomicity per series (FR-IMP-006): if interval persistence fails, the series header is rolled back, but previously imported series remain committed.

---

## §6 — Validation Rules

### 6.1 Series-Level Validation

| Rule | Field(s) | Check | Error |
|------|----------|-------|-------|
| V-S01 | `series_key` | Non-blank, length ≤ 128 | `"series_key is blank or exceeds 128 characters"` |
| V-S02 | `series_type` | Valid `SeriesType` enum value | `"Invalid series_type: {value}"` |
| V-S03 | `asset_id` | Non-null when `series_type ∈ {FORECAST, METERED_ACTUAL}` | `"asset_id required for FORECAST/METERED_ACTUAL series"` |
| V-S04 | `trade_leg_id` | Non-null when `series_type = PROFILE` | `"trade_leg_id required for PROFILE series"` |
| V-S05 | `asset_id` / `trade_leg_id` | Mutually exclusive (for non-asset-linked PPA) | `"Cannot specify both asset_id and trade_leg_id"` (warning for asset-linked PPA) |
| V-S06 | `delivery_end` | `delivery_end > delivery_start` | `"delivery_end must be after delivery_start"` |
| V-S07 | `delivery_timezone` | Valid `ZoneId` | `"Invalid delivery_timezone: {value}"` |
| V-S08 | `volume_unit` | Valid `VolumeUnit` enum value | `"Invalid volume_unit: {value}"` |
| V-S09 | `time_granularity` | Valid `TimeGranularity` enum value | `"Invalid time_granularity: {value}"` |
| V-S10 | `quality_state` | If specified: valid `QualityState` + applicable to series_type | `"quality_state {value} not applicable to {series_type}"` |

### 6.2 Interval-Level Validation

| Rule | Field(s) | Check | Error |
|------|----------|-------|-------|
| V-I01 | `series_key` | Must match a series header row | `"No series metadata found for series_key: {key}"` |
| V-I02 | `interval_start` / `interval_end` | `interval_start < interval_end` | `"interval_end must be after interval_start"` |
| V-I03 | `interval_start` / `interval_end` | Within `[delivery_start, delivery_end)` of the owning series | `"Interval [{start}, {end}) outside delivery range"` |
| V-I04 | `volume` | Non-null, finite | `"volume is required and must be numeric"` |
| V-I05 | `volume` | Precision ≤ 15, scale ≤ 8 | `"volume exceeds precision limits (15,8)"` |
| V-I06 | Overlap | No two intervals within the same series_key may overlap | `"Interval [{start}, {end}) overlaps with [{other_start}, {other_end})"` |
| V-I07 | Duration | Interval duration consistent with `time_granularity` | **Warning** (not error): `"Interval duration {actual} does not match granularity {expected}"` |

### 6.3 Cross-Validation

| Rule | Check | Error |
|------|-------|-------|
| V-X01 | Series header with zero intervals | **Warning**: `"Series {key} has no intervals (empty series)"` |
| V-X02 | Orphan intervals (no matching series header) | **Error**: V-I01 applied |
| V-X03 | Type mismatch with existing series | **Error**: `"Existing series {key} has type {existing_type}; import specifies {import_type}"` (FR-IMP-004) |

---

## §7 — Adapter: CSV Parser

> **TR-IMP-003** — The CSV parser uses standard Java I/O (`BufferedReader`) with no external dependencies. Column positions are detected from the header row (first row). The parser produces `List<SeriesImportRequest>` by grouping interval rows by `series_key`.

### 7.1 Implementation: `CsvImportParser`

**Location:** `pv-persistence/src/main/java/com/power/posval/persistence/adapter/ingest/CsvImportParser.java`

```java
public class CsvImportParser {

    /**
     * Parse series metadata CSV + intervals CSV into import requests.
     * Header row required in both files. Comma-separated.
     */
    public List<SeriesImportRequest> parse(InputStream seriesStream,
                                            InputStream intervalStream) {
        // 1. Parse series metadata → Map<seriesKey, SeriesHeader>
        // 2. Parse intervals → Map<seriesKey, List<IntervalRow>>
        // 3. Join by seriesKey → List<SeriesImportRequest>
        // 4. Orphan intervals → collected as errors
    }
}
```

### 7.2 CSV Format Example — Series Metadata

```csv
series_key,series_type,asset_id,trade_leg_id,volume_unit,time_granularity,delivery_start,delivery_end,delivery_timezone,quality_state,version_id,valid_time
FCST-WP-NORDSEE,FORECAST,WP-NORDSEE,,MW_CAPACITY,MIN_15,2025-01-01T00:00:00+01:00,2030-01-01T00:00:00+01:00,Europe/Berlin,,,
VS-T5500-1,PROFILE,,LEG-1,MW_CAPACITY,MIN_15,2025-03-01T00:00:00+01:00,2025-04-01T00:00:00+02:00,Europe/Berlin,,,
MTR-WP-NORDSEE,METERED_ACTUAL,WP-NORDSEE,,MWH_PER_PERIOD,MIN_15,2025-01-01T00:00:00+01:00,2025-02-01T00:00:00+01:00,Europe/Berlin,PROVISIONAL,,
```

### 7.3 CSV Format Example — Intervals

```csv
series_key,interval_start,interval_end,volume,energy
FCST-WP-NORDSEE,2025-01-01T00:00:00Z,2025-01-01T00:15:00Z,48.500,
FCST-WP-NORDSEE,2025-01-01T00:15:00Z,2025-01-01T00:30:00Z,47.200,
FCST-WP-NORDSEE,2025-01-01T00:30:00Z,2025-01-01T00:45:00Z,49.100,
VS-T5500-1,2025-03-01T00:00:00Z,2025-03-01T00:15:00Z,50.000,12.500
VS-T5500-1,2025-03-01T00:15:00Z,2025-03-01T00:30:00Z,50.000,12.500
MTR-WP-NORDSEE,2025-01-01T00:00:00Z,2025-01-01T00:15:00Z,11.250,11.250
```

Notes:
- Empty `energy` → auto-computed from `volume × (duration / 3600)` for MW_CAPACITY
- Pre-computed `energy = 12.500` for `50.000 MW × 0.25h`
- For MWH_PER_PERIOD, `energy = volume` (pass-through)

---

## §8 — Adapter: Excel Parser

> **TR-IMP-004** — The Excel parser reads `.xlsx` workbooks via Apache POI. Sheet 1 (`SeriesMetadata`) maps to the series header columns; Sheet 2 (`Intervals`) maps to the interval columns. Both sheets use the same column semantics as the CSV format.

### 8.1 Implementation: `ExcelImportParser`

**Location:** `pv-persistence/src/main/java/com/power/posval/persistence/adapter/ingest/ExcelImportParser.java`

**Dependency:** `org.apache.poi:poi-ooxml` (added to `pv-persistence/pom.xml`)

```java
public class ExcelImportParser {

    /**
     * Parse Excel workbook with two sheets.
     * Sheet 1 "SeriesMetadata": series header rows (row 1 = header).
     * Sheet 2 "Intervals": interval rows (row 1 = header).
     */
    public List<SeriesImportRequest> parse(InputStream workbook) {
        // 1. Open XSSFWorkbook
        // 2. Read Sheet "SeriesMetadata" → Map<seriesKey, SeriesHeader>
        // 3. Read Sheet "Intervals" → Map<seriesKey, List<IntervalRow>>
        // 4. Join by seriesKey → List<SeriesImportRequest>
    }
}
```

### 8.2 Excel Layout

| Sheet | Name | Row 1 | Data Rows |
|-------|------|-------|-----------|
| Sheet 1 | `SeriesMetadata` | Column headers (same as CSV §7.2) | One row per series |
| Sheet 2 | `Intervals` | Column headers (same as CSV §7.3) | Many rows per series |

### 8.3 Cell Type Handling

| Column | Expected Cell Type | Fallback |
|--------|--------------------|----------|
| `series_key`, `asset_id`, `trade_leg_id` | STRING | NUMERIC → toString |
| `volume`, `energy` | NUMERIC | STRING → `new BigDecimal(value)` |
| `interval_start`, `interval_end` | STRING (ISO-8601) | NUMERIC (Excel serial date) → convert via `DateUtil` |
| `delivery_start`, `delivery_end` | STRING (ISO-8601 with timezone) | — |
| `version_id` | NUMERIC | STRING → `Long.parseLong(value)` |

---

## §9 — Programmatic API

> **TR-IMP-005** — The programmatic API (`importSeries`) accepts pre-parsed `SeriesImportRequest` objects, bypassing file parsing. This is the primary integration point for system-to-system feeds (weather forecast services, metering APIs, asset management systems).

### 9.1 Usage Pattern

```java
// Weather forecast feed integration
VolumeSeriesImporter importer = ...; // injected

var intervals = weatherService.getForecast("WP-NORDSEE", 2025, 2030)
    .stream()
    .map(wp -> new IntervalRow(wp.start(), wp.end(), wp.capacityMw(), null))
    .toList();

var request = new SeriesImportRequest(
    "FCST-WP-NORDSEE",
    SeriesType.FORECAST,
    "WP-NORDSEE",    // assetId
    null,            // tradeLegId (not applicable for FORECAST)
    VolumeUnit.MW_CAPACITY,
    TimeGranularity.MIN_15,
    new DeliveryPeriod(start, end, ZoneId.of("Europe/Berlin")),
    null,            // qualityState → default CURRENT
    null,            // versionId → auto
    null,            // validTime
    intervals
);

ImportResult result = importer.importSeries(List.of(request), "TN_0042");
```

### 9.2 Multi-Source Batch

Multiple series from different sources can be combined in a single call:

```java
var requests = List.of(
    forecastRequest,     // FORECAST for WP-NORDSEE
    meteredRequest,      // METERED_ACTUAL from TSO
    profileRequest       // PROFILE from trade capture
);
ImportResult result = importer.importSeries(requests, tenantId);
// result.seriesCreated() + result.seriesSuperseded() == 3 (if all succeed)
```

---

## §10 — Auto-Supersession Logic

> **TR-IMP-006** — Auto-supersession follows the same semantics as `VolumeSeriesRepository.supersede()`: the existing version's `quality_state` is transitioned to the terminal state (SUPERSEDED for FORECAST, AMENDED for PROFILE), and a new version is persisted with incremented `version_id`. This triggers downstream revaluation via `VolumeSuperseded` event.

### 10.1 Decision Flow

```
Import request for series_key K:

  findCurrentBySeriesKey(tenantId, K)
     │
     ├── Not found → CREATE
     │     version_id = request.versionId ?? 1
     │     seriesRepo.save(newSeries)
     │     publish VolumePublished
     │
     └── Found (existing) →
           │
           ├── existing.seriesType != request.seriesType
           │     → REJECT (FR-IMP-004, V-X03)
           │
           └── same seriesType → SUPERSEDE
                 version_id = existing.versionId + 1
                 seriesRepo.supersede(existing, newSeries)
                 publish VolumeSuperseded
```

### 10.2 Quality State Transitions on Supersession

| Existing State | Series Type | New State (Existing) | New Version State |
|----------------|-------------|----------------------|-------------------|
| CURRENT | FORECAST | SUPERSEDED | CURRENT |
| EFFECTIVE | PROFILE | AMENDED | EFFECTIVE |
| PROVISIONAL | METERED_ACTUAL | SUPERSEDED | PROVISIONAL |
| VALIDATED | METERED_ACTUAL | — (reject: terminal) | — |

If the existing series is in a terminal state (AMENDED, SUPERSEDED, VALIDATED), supersession is rejected — the series has already been replaced or finalized.

---

## §11 — Batch Persistence & Performance

### 11.1 Persistence Pipeline

```
SeriesImportRequest
  │
  ├── Build DefaultVolumeSeries (via Builder)
  │     ├── series header fields
  │     └── intervals → TreeSet<DefaultVolumeInterval>
  │
  ├── JpaVolumeSeriesRepository.save(series)
  │     ├── em.persist(seriesEntity)          ← single header row
  │     └── batchWriter.writeAll(intervals)   ← flush/clear every pv.batch.size
  │
  └── DomainEventPublisher.publish(event)     ← outbox insert (same tx)
```

### 11.2 Performance Targets

| Metric | Target | Basis |
|--------|--------|-------|
| 175k intervals (single series) | < 30 seconds | 5-year PPA @ 15-min granularity |
| 10 series × 17.5k intervals each | < 60 seconds | Multi-asset portfolio import |
| Memory footprint | < 256 MB heap delta | Streaming parse, per-series flush |
| Batch size | Configurable via `pv.batch.size` | Default 50 (Pattern #20, TR-017) |

### 11.3 Memory Management

For very large imports (millions of intervals across many series), the parser streams intervals grouped by `series_key`:

1. Parse series metadata into `Map<String, SeriesHeader>` (small — one entry per series)
2. Stream interval rows, grouping by `series_key`
3. When a series group is complete (all intervals collected), validate + persist + flush
4. Move to next series group

This ensures memory usage scales with the largest single series, not the total file size.

---

## §12 — Event Publishing

### 12.1 Events on Import

| Scenario | Event | Key Fields |
|----------|-------|------------|
| New series created | `VolumePublished` | `seriesKey`, `layer=VOLUME`, `seriesType`, `versionId`, `deliveryRange`, `granularity`, `qualityState`, `scope="FULL"`, `eventTime=Instant.now()` |
| Existing series superseded | `VolumeSuperseded` | `seriesKey`, `layer=VOLUME`, `seriesType`, `affectedRange=deliveryPeriod`, `oldVersionId`, `newVersionId`, `qualityState`, `eventTime=Instant.now()` |

### 12.2 Downstream Impact

- `VolumeSuperseded` → triggers settlement cell revaluation (S5a) for all dependent positions
- `VolumePublished` → triggers forward mark recalculation (S5b) and cache invalidation (S6)
- Events are written to the outbox table within the same transaction as series persistence (Pattern #24, TR-014)

---

## §13 — Idempotency

> **TR-IMP-007** — Idempotency is enforced via the `(tenantId, seriesKey, versionId)` composite key. If a series with the same key and version already exists (regardless of state), the import treats it as already-processed and skips it. This aligns with Pattern #28 (IdempotentConsumer).

### 13.1 Idempotency Check

```java
// Before creating or superseding:
Optional<VolumeSeries> existing = seriesRepo.findCurrentBySeriesKey(tenantId, seriesKey);

if (existing.isPresent() && existing.get().versionId() == request.versionId()) {
    // Already imported — skip (idempotent)
    return;
}
```

### 13.2 Re-Import Scenarios

| Scenario | Behavior |
|----------|----------|
| Same key, same version_id | Skip (already processed) |
| Same key, higher version_id | Supersede existing, create new |
| Same key, lower version_id | Skip (stale — newer version exists) |
| Same key, auto version_id | Supersede (version auto-incremented) |
| Different key | Create new (independent series) |

---

## §14 — Error Handling

### 14.1 Error Strategy

- **Per-series isolation** (FR-IMP-006): each series is an independent unit-of-work. If series A fails validation, series B and C are still imported.
- **Fail-fast within series**: if any validation rule fails for a series, the entire series is skipped (no partial interval import).
- **Error accumulation**: all errors are collected in `ImportResult.errors()` with line numbers and field references.

### 14.2 Error Categories

| Category | Severity | Example | Behavior |
|----------|----------|---------|----------|
| Parse error | Error | Invalid CSV format, missing header | Abort file, return errors |
| Series validation | Error | Missing required field, type mismatch | Skip series, continue |
| Interval validation | Error | Overlap, out-of-range, null volume | Skip series, continue |
| Granularity mismatch | Warning | 30-min interval on MIN_15 series | Include in errors, still import |
| Empty series | Warning | Series header with zero intervals | Include in errors, still import |
| Persistence failure | Error | Database constraint violation | Skip series, continue |

---

## §15 — Guice Wiring

### 15.1 Module Bindings

```java
/**
 * Guice module for volume series import. Extends PersistenceModule.
 */
public class ImportModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(VolumeSeriesImporter.class)
            .to(DefaultVolumeSeriesImporter.class)
            .in(Singleton.class);

        bind(CsvImportParser.class).in(Singleton.class);
        bind(ExcelImportParser.class).in(Singleton.class);
    }
}
```

### 15.2 Dependency Graph

```
DefaultVolumeSeriesImporter
  ├── VolumeSeriesRepository  (existing, from PersistenceModule)
  ├── DomainEventPublisher    (existing, from EventModule)
  ├── NumericPrecision        (existing, from DomainModule)
  ├── UnitOfWork              (existing, from PersistenceModule)
  ├── CsvImportParser         (new, from ImportModule)
  └── ExcelImportParser       (new, from ImportModule)
```

---

## §16 — Testing Strategy

### 16.1 Unit Tests

| Test | Scope | Location |
|------|-------|----------|
| `SeriesImportRequestValidationTest` | All V-S* and V-I* validation rules | `pv-domain/test/` |
| `EnergyComputationTest` | Energy auto-computation from volume + duration | `pv-domain/test/` |
| `SupersessionLogicTest` | Auto-supersede, type mismatch, terminal state rejection | `pv-domain/test/` |
| `IdempotencyTest` | Skip on duplicate `(key, version)` | `pv-domain/test/` |
| `CsvImportParserTest` | Header detection, column mapping, error lines | `pv-persistence/test/` |
| `ExcelImportParserTest` | Two-sheet parsing, cell type handling | `pv-persistence/test/` |

### 16.2 Integration Tests

| Test | Scope | Location |
|------|-------|----------|
| `CsvToRepositoryIntegrationTest` | CSV → parse → validate → in-memory repo → verify | `pv-kafka/test/` |
| `SupersessionIntegrationTest` | Import → supersede → verify event published | `pv-kafka/test/` |
| `MultiSeriesImportTest` | Batch of 3 series (1 fail, 2 succeed) → verify partial success | `pv-kafka/test/` |

### 16.3 Performance Benchmark

| Test | Target | Location |
|------|--------|----------|
| `FiveYearImportBenchmark` | 175k intervals < 30s | `pv-domain/test/benchmark/` |

---

## §17 — Module Structure

### 17.1 New Files

| File | Module | Purpose |
|------|--------|---------|
| `VolumeSeriesImporter.java` | `pv-domain` | Port interface |
| `SeriesImportRequest.java` | `pv-domain` | Request record |
| `IntervalRow.java` | `pv-domain` | Interval row record |
| `ImportResult.java` | `pv-domain` | Result record |
| `ImportError.java` | `pv-domain` | Error record |
| `DefaultVolumeSeriesImporter.java` | `pv-domain` | Service implementation |
| `CsvImportParser.java` | `pv-persistence` | CSV adapter |
| `ExcelImportParser.java` | `pv-persistence` | Excel adapter |
| `ImportModule.java` | `pv-guice` | Guice wiring |

### 17.2 Modified Files

| File | Change |
|------|--------|
| `pv-persistence/pom.xml` | Add Apache POI dependency |

---

## §18 — Compliance Matrix

### 18.1 Import Requirements → Spec Artifacts

| Requirement | Description | Spec Artifacts |
|-------------|-------------|----------------|
| FR-IMP-001 | CSV, Excel, programmatic API | `VolumeSeriesImporter`, `CsvImportParser`, `ExcelImportParser` |
| FR-IMP-002 | Two-file linked by series_key | §3, `SeriesImportRequest` |
| FR-IMP-003 | Auto-supersession | §10, `DefaultVolumeSeriesImporter`, `VolumeSeriesRepository.supersede()` |
| FR-IMP-004 | Type mismatch rejection | §10.1, V-X03 |
| FR-IMP-005 | Energy auto-computation | §5.3, `VolumeUnit.toEnergy()` |
| FR-IMP-006 | Per-series isolation | §5.4, `UnitOfWork` |
| FR-IMP-007 | Structured ImportResult | §4.2, `ImportResult`, `ImportError` |
| FR-IMP-008 | BatchWriter persistence | §11.1, `BatchWriter`, Pattern #20 |
| FR-IMP-009 | Event publishing | §12, `VolumePublished`, `VolumeSuperseded`, Pattern #24 |
| FR-IMP-010 | Idempotency | §13, Pattern #28 |
| FR-IMP-011 | Delivery range containment | §6.2, V-I03 |
| FR-IMP-012 | Overlap rejection | §6.2, V-I06 |
| FR-IMP-013 | D-11 ownership validation | §6.1, V-S03, V-S04, V-S05 |
| FR-IMP-014 | All granularity support | §6.1, V-S09 |
| FR-IMP-015 | Performance: 175k < 30s | §11.2 |
| FR-IMP-016 | Streaming memory management | §11.3 |

### 18.2 Existing Patterns & Technical Rules Referenced

| Pattern/TR | Where Used |
|-----------|------------|
| Pattern #14 (Observer/Domain Events) | §12 — VolumePublished, VolumeSuperseded |
| Pattern #18 (Repository Port+Adapter) | §4 — VolumeSeriesImporter port |
| Pattern #20 (Unit of Work / Batch Flush) | §11 — BatchWriter |
| Pattern #24 (Transactional Outbox) | §12 — Events in same tx as persistence |
| Pattern #28 (Idempotent Consumer) | §13 — (tenantId, seriesKey, versionId) dedup |
| D-11 (Unified volume resolution) | §6.1 — FORECAST vs PROFILE ownership |
| TR-017 (BatchWriter flush/clear) | §11 — pv.batch.size configurable |
| TR-014 (Outbox write in same tx) | §12 — Event publishing |
