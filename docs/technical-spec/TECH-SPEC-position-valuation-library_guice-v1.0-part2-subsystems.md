# Technical Specification — Position & Valuation (Library + Guice) v1.0

## Part 2 — Subsystems (§8–§13)

| Part | File | Sections |
|------|------|----------|
| Part 1 | `TECH-SPEC-…-part1-domain-and-ports.md` | §1–§7 |
| **Part 2 (this file)** | `TECH-SPEC-…-part2-subsystems.md` | §8–§13 |
| Part 3 | `TECH-SPEC-…-part3-crosscutting-and-wiring.md` | §14–§22 |

---

## §8 — S1: Position Ledger

### 8.1 Bitemporal JPA Entity — Pattern #1, #6, #23, #34, #35, FR-006, FR-030, D-1, S1

The `PositionLedgerEntryEntity` JPA skeleton is defined in Part 1 §6.3. This section covers the repository port, adapter implementation, command handlers, and outbox integration.

> **TR-011** — The position ledger is append-only. Supersession closes `known_to` on the prior version and appends a new row. No UPDATE or DELETE at the application layer. The adapter enforces this by exposing only `save()` and query methods — no `update()` or `delete()` on the port interface. (Extends FR-006, FR-037, D-1.)

### 8.2 `PositionLedgerRepository` Port — Pattern #18, FR-007, FR-030, S1

```java
/**
 * Port interface for position ledger persistence.
 * Lives in pv-domain/port/repository/. No JPA, no framework annotations.
 * FR-007: as-of reconstruction via bitemporal predicate.
 * FR-030: grain = trade-leg × delivery-month block.
 */
public interface PositionLedgerRepository {

    /**
     * Persist a new ledger entry (append-only). Never updates existing rows.
     * FR-006: known_from set to processing time; known_to = null (current knowledge).
     */
    void save(PositionLedgerEntry entry);

    /**
     * Current-knowledge entries for a trade-leg across all delivery months.
     * Equivalent to: WHERE known_to IS NULL AND trade_id = ? AND trade_leg_id = ?
     */
    List<PositionLedgerEntry> findCurrentByTradeLeg(String tenantId,
                                                     String tradeId,
                                                     String tradeLegId);

    /**
     * Bitemporal as-of reconstruction (FR-007).
     * Returns entries valid at business date B as known at knowledge date K.
     */
    List<PositionLedgerEntry> findAsOf(String tenantId,
                                       String tradeId,
                                       Instant businessDate,
                                       Instant knowledgeDate);

    /**
     * All current-knowledge entries within a delivery range for a tenant.
     * Used by slot-cache population and portfolio queries.
     */
    List<PositionLedgerEntry> findByDeliveryRange(String tenantId,
                                                   Instant deliveryStart,
                                                   Instant deliveryEnd);

    /**
     * Supersede existing entries by closing known_to, then saving new versions.
     * FR-037: knowledge-time supersession; valid_from from amendment event.
     * @param entriesToClose entries whose known_to will be set to now()
     * @param newEntries     replacement entries with known_from = now()
     */
    void supersede(List<PositionLedgerEntry> entriesToClose,
                   List<PositionLedgerEntry> newEntries);
}
```

### 8.3 `JpaPositionLedgerRepository` Adapter — Pattern #18, FR-007, S1

> **TR-012** — The bitemporal as-of query predicate `valid_from ≤ B < valid_to AND known_from ≤ K < known_to` is the canonical reconstruction filter. Open-ended `valid_to` and `known_to` (NULL = current/open) require explicit IS NULL handling in the predicate. (Extends FR-007.)

```java
/**
 * JPA adapter for PositionLedgerRepository. Lives in pv-persistence.
 * Injected EntityManager via @Inject Provider<EntityManager>.
 */
public class JpaPositionLedgerRepository implements PositionLedgerRepository {

    @Inject private Provider<EntityManager> emProvider;

    @Override
    public List<PositionLedgerEntry> findAsOf(String tenantId,
                                               String tradeId,
                                               Instant businessDate,
                                               Instant knowledgeDate) {
        EntityManager em = emProvider.get();
        return em.createQuery("""
            SELECT e FROM PositionLedgerEntryEntity e
            WHERE e.tenantId   = :tenantId
              AND e.tradeId    = :tradeId
              AND e.validFrom <= :businessDate
              AND (e.validTo   IS NULL OR e.validTo > :businessDate)
              AND e.knownFrom <= :knowledgeDate
              AND (e.knownTo   IS NULL OR e.knownTo > :knowledgeDate)
            ORDER BY e.tradeLegId, e.deliveryStart
            """, PositionLedgerEntryEntity.class)
            .setParameter("tenantId", UUID.fromString(tenantId))
            .setParameter("tradeId", tradeId)
            .setParameter("businessDate", businessDate)
            .setParameter("knowledgeDate", knowledgeDate)
            .getResultStream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    public void supersede(List<PositionLedgerEntry> entriesToClose,
                          List<PositionLedgerEntry> newEntries) {
        EntityManager em = emProvider.get();
        Instant now = Instant.now();

        // Close prior versions (knowledge-time supersession)
        for (PositionLedgerEntry entry : entriesToClose) {
            em.createQuery("""
                UPDATE PositionLedgerEntryEntity e
                SET e.knownTo = :now
                WHERE e.entryUuid = :uuid
                  AND e.knownTo IS NULL
                """)
                .setParameter("now", now)
                .setParameter("uuid", entry.id())
                .executeUpdate();
        }

        // Append new versions
        for (PositionLedgerEntry entry : newEntries) {
            em.persist(toEntity(entry));
        }
    }

    // ... save(), findCurrentByTradeLeg(), findByDeliveryRange() — standard JPA
}
```

### 8.4 Command Handlers — Pattern #16, FR-032, FR-037, S1

> **TR-013** — Trade-lifecycle commands (`TradeCapture`, `TradeAmend`, `TradeCancel`) are the exclusive producers of position-ledger versions. Each command handler writes the ledger entry AND the outbox row in the same JPA transaction (Pattern #24). No position version is created outside a trade event. (Extends FR-037.)

```java
/**
 * Port interface for trade command handling. Lives in pv-domain.
 * Each handler produces ledger entries and domain events.
 */
public interface TradeCaptureHandler {

    /**
     * Process initial trade capture → create position ledger entries.
     * FR-030: one entry per delivery-month block.
     * FR-034: signed quantity (+long, -short).
     * @return created entries for downstream event publishing
     */
    List<PositionLedgerEntry> handle(TradeCapture command);
}

public interface TradeAmendHandler {

    /**
     * Process trade amendment → supersede existing entries, create new versions.
     * FR-008: backdated corrections move knowledge time only;
     *         forward-effective changes move valid time.
     * FR-037: amendment carries both processing time and business-effective date.
     */
    List<PositionLedgerEntry> handle(TradeAmend command);
}

public interface TradeCancelHandler {

    /**
     * Process trade cancellation → close all current entries.
     * Sets status = CANCELLED, closes known_to on all current-knowledge entries.
     */
    List<PositionLedgerEntry> handle(TradeCancel command);
}
```

**Representative handler excerpt** — `TradeCaptureHandler` implementation:

```java
/**
 * Domain service implementing TradeCapture. Lives in pv-domain/service/.
 * Uses port interfaces only — no JPA, no framework.
 */
public class DefaultTradeCaptureHandler implements TradeCaptureHandler {

    private final PositionLedgerRepository ledgerRepo;
    private final DomainEventPublisher eventPublisher;

    @Inject
    public DefaultTradeCaptureHandler(PositionLedgerRepository ledgerRepo,
                                       DomainEventPublisher eventPublisher) {
        this.ledgerRepo = ledgerRepo;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public List<PositionLedgerEntry> handle(TradeCapture cmd) {
        // FR-030: decompose delivery period into monthly blocks
        List<DeliveryRange> monthBlocks = cmd.deliveryPeriod()
            .toMonthBlocks(cmd.deliveryTimezone());

        List<PositionLedgerEntry> entries = monthBlocks.stream()
            .map(block -> PositionLedgerEntry.builder()
                .tenantId(cmd.tenantId())
                .tradeId(cmd.tradeId())
                .tradeLegId(cmd.tradeLegId())
                .tradeVersion(cmd.tradeVersion())
                .deliveryRange(block)
                .quantity(cmd.quantity())             // FR-034: signed
                .volumeUnit(cmd.volumeUnit())
                .priceExpressionId(cmd.priceExpressionId())
                .portfolioId(cmd.portfolioId())
                .deliveryPointId(cmd.deliveryPointId())
                .volumeSeriesKey(cmd.volumeSeriesKey())  // nullable for flat-block
                .validFrom(cmd.effectiveDate())
                .knownFrom(Instant.now())
                .status("ACTIVE")
                .build())
            .toList();

        entries.forEach(ledgerRepo::save);

        // Pattern #24: outbox write in same transaction
        eventPublisher.publish(new PositionCaptured(
            cmd.tenantId(), cmd.tradeId(), cmd.tradeLegId(),
            cmd.tradeVersion(), entries.size(), Instant.now()));

        return entries;
    }
}
```

### 8.5 Outbox Write — Pattern #24, D-9, FR-106, S1

> **TR-014** — Domain events are written to the `trade.outbox` table within the same JPA transaction as the ledger mutation. The `OutboxRelayProducer` (in `pv-kafka`) polls unpublished rows and relays to Kafka after commit. Ordering guarantee: events for the same aggregate are published in `created_at` order. Relay marks `published_at` only after broker ACK. (Extends D-9, V2.0 §13.4.)

The `DomainEventPublisher` port and its outbox adapter are defined in Part 3 §15.

---

## §9 — S3: Volume Series

### 9.1 `VolumeResolver` Sealed Interface — Pattern #9, D-11, FR-050, FR-051, S3

> **TR-015** — `VolumeResolver` is a `sealed interface` with two permits: `ProfileResolver` and `ForecastResolver`. In practice, the unified resolution model (D-11) means both resolvers execute the same multiplication: `interval.volume × reference.multiplier`. The distinction exists for type-safety and logging, not for branching logic. (Extends D-11, FR-051.)

```java
/**
 * Sealed volume resolution strategy. Both implementations apply the same
 * formula: volume × multiplier (D-11). The seal provides exhaustive dispatch
 * and type-specific logging/metrics, not behavioral branching.
 *
 * Lives in pv-domain/service/.
 */
public sealed interface VolumeResolver
    permits ProfileResolver, ForecastResolver {

    /**
     * Resolve volume for a trade-leg over an interval range.
     * @param ref           the VolumeReference linking trade to series
     * @param intervalRange half-open [start, end) in market-local time
     * @param purpose       FORWARD or SETTLEMENT — determines series selection (FR-048e)
     * @return resolved volume records with multiplier applied
     */
    List<VolumeRecord> resolve(VolumeReference ref,
                                DeliveryRange intervalRange,
                                ResolutionPurpose purpose);
}
```

```java
/**
 * Resolves volume for PROFILE series (per-trade, multiplier=1.0).
 * FR-051a: PROFILE is the designed path for DA/bilateral settlement —
 * not a fallback. meteredSeriesKey is null by design.
 */
public record ProfileResolver(
    VolumeSeriesRepository seriesRepo
) implements VolumeResolver {

    @Override
    public List<VolumeRecord> resolve(VolumeReference ref,
                                       DeliveryRange intervalRange,
                                       ResolutionPurpose purpose) {
        // D-11: same resolution for all purposes — PROFILE has no metered fallback
        var series = seriesRepo.findCurrentBySeriesKey(
            ref.tenantId(), ref.volumeSeriesKey());
        return series.intervals().stream()
            .filter(i -> overlaps(i, intervalRange))
            .map(i -> new VolumeRecord(
                i.intervalStart(), i.intervalEnd(),
                i.volume().multiply(ref.multiplier()),
                i.energy().multiply(ref.multiplier()),
                series.versionId(), series.qualityState(),
                series.seriesType(), null, ref.multiplier()))
            .toList();
    }
}
```

```java
/**
 * Resolves volume for FORECAST series (per-asset, shared, multiplier < 1.0).
 * FR-051a: SETTLEMENT purpose reads meteredSeriesKey if set;
 *          FORWARD purpose reads volumeSeriesKey (forecast).
 */
public record ForecastResolver(
    VolumeSeriesRepository seriesRepo,
    MeteredActualRepository meteredRepo
) implements VolumeResolver {

    @Override
    public List<VolumeRecord> resolve(VolumeReference ref,
                                       DeliveryRange intervalRange,
                                       ResolutionPurpose purpose) {
        if (purpose == ResolutionPurpose.SETTLEMENT
                && ref.meteredSeriesKey() != null) {
            // FR-051a: settlement reads metered actuals × multiplier
            return resolveFromMetered(ref, intervalRange);
        }
        // FR-051a: forward reads forecast × multiplier
        return resolveFromForecast(ref, intervalRange);
    }

    // ... resolveFromMetered(), resolveFromForecast() — same multiplication pattern
}
```

### 9.2 `VolumeSeriesFactory` — Pattern #7, D-11, FR-050, S3

```java
/**
 * Factory for creating VolumeSeries with correct ownership routing.
 * D-11: PROFILE (per-trade, multiplier=1.0) vs FORECAST (per-asset, shared).
 * FORECAST series are typically created during asset onboarding, not trade capture.
 * Lives in pv-domain/service/.
 */
public class VolumeSeriesFactory {

    /**
     * Create a PROFILE series for a trade-leg (DA/bilateral).
     * FR-050: per-trade, dedicated, multiplier=1.0.
     * @return new VolumeSeries with seriesType=PROFILE, tradeLegId set, assetId null
     */
    public VolumeSeries createProfile(String tenantId,
                                       String tradeLegId,
                                       String tradeId,
                                       int tradeVersion,
                                       DeliveryPeriod deliveryPeriod,
                                       VolumeUnit volumeUnit,
                                       TimeGranularity granularity,
                                       List<VolumeInterval> intervals) {
        // Validation: PROFILE requires tradeLegId, no assetId
        Objects.requireNonNull(tradeLegId, "tradeLegId required for PROFILE");
        // ... construct and return
    }

    /**
     * Create or locate existing FORECAST series for an asset.
     * FR-050: per-asset, shared — one series per (assetId, delivery window).
     * If a current FORECAST series already exists for this asset, return it.
     * @return existing or new VolumeSeries with seriesType=FORECAST
     */
    public VolumeSeries createOrGetForecast(String tenantId,
                                             String assetId,
                                             DeliveryPeriod deliveryPeriod,
                                             VolumeUnit volumeUnit,
                                             TimeGranularity granularity) {
        // Validation: FORECAST requires assetId, no tradeLegId
        Objects.requireNonNull(assetId, "assetId required for FORECAST");
        // ... lookup existing or construct new
    }
}
```

### 9.3 `VolumeSeriesRepository` + `VolumeSeriesSpec` — Pattern #18, #19, FR-051, S3

```java
/**
 * Port interface for volume series persistence.
 * Lives in pv-domain/port/repository/.
 */
public interface VolumeSeriesRepository {

    void save(VolumeSeries series);

    Optional<VolumeSeries> findById(UUID id);

    /**
     * Find current (non-superseded) series by series key.
     * WHERE quality_state IN ('CURRENT', 'EFFECTIVE')
     */
    Optional<VolumeSeries> findCurrentBySeriesKey(String tenantId, String seriesKey);

    /**
     * Find series matching a composed specification.
     * Pattern #19: functional-interface specification composable via .and()/.or().
     */
    List<VolumeSeries> findAll(String tenantId, VolumeSeriesSpec spec);

    /**
     * Check existence for idempotent consumer (Pattern #28).
     */
    boolean existsByTradeIdAndTradeVersion(String tradeId, int tradeVersion);

    /**
     * Supersede a series: mark old version as SUPERSEDED, persist new version.
     */
    void supersede(VolumeSeries oldVersion, VolumeSeries newVersion);
}
```

```java
/**
 * Composable query specification for volume series lookups.
 * Pattern #19: functional interface, composed via .and()/.or().
 * Lives in pv-domain/port/repository/.
 *
 * The pv-persistence adapter translates these to JPA CriteriaQuery predicates.
 */
@FunctionalInterface
public interface VolumeSeriesSpec {

    /**
     * Apply this specification to a CriteriaBuilder query.
     * @return predicate representing this filter condition
     */
    jakarta.persistence.criteria.Predicate toPredicate(
        jakarta.persistence.criteria.Root<?> root,
        jakarta.persistence.criteria.CriteriaQuery<?> query,
        jakarta.persistence.criteria.CriteriaBuilder cb);

    /**
     * Compose with AND.
     */
    default VolumeSeriesSpec and(VolumeSeriesSpec other) {
        return (root, query, cb) -> cb.and(
            this.toPredicate(root, query, cb),
            other.toPredicate(root, query, cb));
    }

    /**
     * Compose with OR.
     */
    default VolumeSeriesSpec or(VolumeSeriesSpec other) {
        return (root, query, cb) -> cb.or(
            this.toPredicate(root, query, cb),
            other.toPredicate(root, query, cb));
    }

    // Static factories
    static VolumeSeriesSpec byAsset(String assetId) {
        return (root, query, cb) -> cb.equal(root.get("assetId"), assetId);
    }

    static VolumeSeriesSpec byTradeLeg(String tradeLegId) {
        return (root, query, cb) -> cb.equal(root.get("tradeLegId"), tradeLegId);
    }

    static VolumeSeriesSpec currentVersionOnly() {
        return (root, query, cb) -> root.get("qualityState")
            .in(QualityState.CURRENT, QualityState.EFFECTIVE);
    }

    static VolumeSeriesSpec bySeriesType(SeriesType type) {
        return (root, query, cb) -> cb.equal(root.get("seriesType"), type);
    }

    static VolumeSeriesSpec withinDeliveryRange(Instant start, Instant end) {
        return (root, query, cb) -> cb.and(
            cb.lessThan(root.get("deliveryStart"), end),
            cb.greaterThanOrEqualTo(root.get("deliveryEnd"), start));
    }
}
```

**Usage example:**

```java
var spec = VolumeSeriesSpec.byAsset("WP-NORDSEE")
    .and(VolumeSeriesSpec.currentVersionOnly())
    .and(VolumeSeriesSpec.bySeriesType(SeriesType.FORECAST));

List<VolumeSeries> results = repo.findAll(tenantId, spec);
```

### 9.4 `MaterializationStrategy` Sealed Interface — Pattern #11, FR-056, V3.0 §4.1–§4.3, S3, S6b

> **TR-016** — `MaterializationStrategy` is a `sealed interface` with three permits corresponding to V3.0 §4.1–§4.3: `EagerStrategy` (short-tenor), `RollingHorizonStrategy` (long-tenor PPA/bilateral), and `ChunkStrategy` (per-month parallel chunk). Selection is data-driven: based on `totalExpectedIntervals` and `seriesType`. (Extends FR-056.)

```java
/**
 * Sealed materialization strategy for volume series interval generation.
 * Lives in pv-domain/service/.
 */
public sealed interface MaterializationStrategy
    permits EagerStrategy, RollingHorizonStrategy, ChunkStrategy {

    /**
     * Materialize intervals for the given series.
     * @param series target volume series
     * @param writer batch writer for persistence (Pattern #20)
     * @param publisher event publisher for VolumePublished/ChunkMaterialized
     */
    void materialize(VolumeSeries series,
                     BatchWriter writer,
                     DomainEventPublisher publisher);
}
```

**`RollingHorizonStrategy` excerpt:**

```java
/**
 * Rolling-horizon: materialize M+1 through M+3, enqueue remaining as chunks.
 * V3.0 §4.2: long-tenor trades (PPAs, multi-year bilateral).
 */
public record RollingHorizonStrategy(
    int horizonMonths,    // default: 3 (M+1..M+3)
    ChunkEnqueuer enqueuer
) implements MaterializationStrategy {

    @Override
    public void materialize(VolumeSeries series,
                            BatchWriter writer,
                            DomainEventPublisher publisher) {
        YearMonth now = YearMonth.now();
        YearMonth through = now.plusMonths(horizonMonths);

        // Near-term: materialize immediately
        List<VolumeInterval> nearTerm = generateIntervals(
            series, now.atDay(1), through.atEndOfMonth());
        writer.writeAll(nearTerm);

        // Update series status
        series.setMaterializationStatus(MaterializationStatus.PARTIAL);
        series.setMaterializedThrough(through);

        publisher.publish(new VolumePublished(
            series.seriesKey().value(), VolumeLayer.VOLUME,
            series.seriesType(), series.versionId(),
            series.deliveryRange(), series.granularity(),
            series.qualityState(), "PARTIAL", Instant.now()));

        // Far-dated: enqueue monthly chunks (Kafka messages)
        // V3.0 §4.3: each chunk is an independent Kafka message
        YearMonth month = through.plusMonths(1);
        YearMonth end = YearMonth.from(series.deliveryPeriod().end());
        while (!month.isAfter(end)) {
            enqueuer.enqueue(series.seriesKey(), month);
            month = month.plusMonths(1);
        }
    }
}
```

### 9.5 `BatchWriter` — Pattern #20, V2.0 §17, S3

> **TR-017** — `BatchWriter` wraps `EntityManager.persist()` with periodic `flush()` + `clear()` every 50 entities. Hibernate `hibernate.jdbc.batch_size` is configured to 50 in `persistence.xml` (or 1000 for chunk processing per V2.0 §17). `order_inserts=true` is mandatory — without it, Hibernate interleaves per entity type, degrading batch throughput ~5×. (Extends V2.0 §17.1.)

```java
/**
 * Batch writer for efficient JPA bulk inserts.
 * Lives in pv-persistence/batch/.
 * Flushes every batchSize entities to prevent first-level cache bloat.
 */
public class BatchWriter {

    private final Provider<EntityManager> emProvider;
    private final int batchSize;

    @Inject
    public BatchWriter(Provider<EntityManager> emProvider) {
        this(emProvider, 50);  // default; chunk processor uses 1000
    }

    public BatchWriter(Provider<EntityManager> emProvider, int batchSize) {
        this.emProvider = emProvider;
        this.batchSize = batchSize;
    }

    /**
     * Persist all entities with periodic flush/clear.
     * Pattern #20: prevents OOM on large batches (2,976+ intervals per chunk).
     */
    public <T> void writeAll(List<T> entities) {
        EntityManager em = emProvider.get();
        for (int i = 0; i < entities.size(); i++) {
            em.persist(entities.get(i));
            if ((i + 1) % batchSize == 0) {
                em.flush();
                em.clear();
            }
        }
        // Final flush for remainder
        if (entities.size() % batchSize != 0) {
            em.flush();
            em.clear();
        }
    }
}
```

### 9.6 Event Publishing — Pattern #27, FR-052a, FR-052c, V3.0 §8, S3

> **TR-019** — Volume events (`VolumePublished`, `VolumeSuperseded`, `VolumeChunkMaterialized`) carry sufficient state for downstream routing without fetching the event source: `series_key + version_id + delivery_range + quality_state`. The `DomainEventPublisher` port writes to the outbox within the same transaction as the volume mutation. (Extends FR-052a, Pattern #27.)

Event record declarations are in Part 1 §5.2. The `DomainEventPublisher` port:

```java
/**
 * Port interface for publishing domain events to the outbox.
 * Lives in pv-domain/port/event/.
 * Implementation writes to trade.outbox table (Pattern #24).
 */
public interface DomainEventPublisher {

    /**
     * Publish a domain event. Must be called within the same transaction
     * as the data mutation. The outbox relay (pv-kafka) handles Kafka delivery.
     */
    void publish(Object event);
}
```

**Consumer behavior on `VolumeSuperseded`** (FR-052b):

| Supersession type | Consumer action |
|-------------------|----------------|
| `METERED_ACTUAL` | Locate trade-legs via `VolumeReference.meteredSeriesKey`; re-resolve settlement cells (S5a) for each trade-leg × affected intervals × multiplier |
| `FORECAST` | Locate trade-legs via `VolumeReference.volumeSeriesKey`; overwrite forward marks (S5b) — ephemeral, no bitemporality |
| `PROFILE` (trade amendment) | Informational to volume consumers; position/valuation cascade driven by trade-amendment event (FR-052b point 4) |

---

## §10 — S4: Market Data Store

### 10.1 `MarketDataPort` — Pattern #10, FR-060, FR-061, FR-063, S4

> **TR-020** — `MarketDataPort` is a read-only port interface. The market data store is external to the position/valuation module. Version pinning ensures reproducibility: every lookup returns `(value, versionId)` and the caller records the `versionId` in the valuation cell's `input_version_set` (FR-056). (Extends FR-060, FR-063.)

```java
/**
 * Read-only port to the Market Data Store (S4).
 * Lives in pv-domain/port/marketdata/.
 * Implementation may be a REST client, gRPC stub, or in-process adapter.
 *
 * FR-060: facts stored once, referenced by version.
 * FR-061: each series independently versioned.
 * FR-063: forward curves at pillars; expansion to atomic intervals is the
 *          consumer's responsibility (or the port implementation's, per O-6).
 */
public interface MarketDataPort {

    /**
     * Look up a fixing value for a specific interval.
     * Used by PriceEvaluator for MarketDataLeaf resolution.
     * @param series       market data series identifier (e.g., "EPEX-DE-LU-DA15")
     * @param intervalStart interval start in market-local time
     * @return fixing value with version for reproducibility
     */
    MarketDataLookup lookupFixing(String series, Instant intervalStart);

    /**
     * Look up a macro index value for a reference period.
     * Used by PriceEvaluator for IndexLeaf resolution (e.g., HICP).
     * @param series           index series identifier (e.g., "HICP-DE")
     * @param refMonthExpr     reference month expression (e.g., "deliveryYear-1:November")
     * @param deliveryPeriod   the delivery period for reference-month resolution
     */
    MarketDataLookup lookupIndex(String series,
                                  String refMonthExpr,
                                  DeliveryPeriod deliveryPeriod);

    /**
     * Look up forward curve value at a specific pillar.
     * FR-063: curve version returned; expansion rule applied by caller.
     */
    MarketDataLookup lookupForwardCurve(String series,
                                         YearMonth pillar,
                                         Instant asOfDate);

    /**
     * Look up FX rate for a currency pair at a reference date.
     */
    MarketDataLookup lookupFxRate(String currencyPair, Instant referenceDate);

    /**
     * Pinned version lookup — fetch a specific version of a series.
     * Used for reproducibility: "re-derive settlement with exact inputs."
     * FR-060: facts stored once, referenced by version.
     */
    MarketDataLookup lookupAtVersion(String series,
                                      Instant intervalStart,
                                      long versionId);
}
```

```java
/**
 * Result of a market data lookup. Carries value + version for input_version_set.
 * FR-056: every valuation cell records the version_id of each input.
 */
public record MarketDataLookup(
    BigDecimal value,
    long versionId,
    String series,
    Instant referenceTime,
    QualityState qualityState    // e.g., PROVISIONAL, VALIDATED
) {}
```

---

## §11 — S5: Valuation

### 11.1 Settlement Cells (S5a) — Pattern #1, #34, #35, FR-070, FR-071, FR-072, D-3, S5a

> **TR-021** — Settlement cells are durable bitemporal measures. A cell is created only when ALL mandatory inputs are available for the interval (FR-071a): the fixing price(s) from S4 AND the metered volume from S3 (for asset-linked trades). A cell is never created from partial inputs. (Extends FR-071, FR-071a.)

#### JPA Entity Skeleton (`pv-persistence`)

```java
@Entity
@Table(name = "settlement_cell", schema = "valuation")
@EntityListeners(BitemporalAuditListener.class)     // Pattern #35
public class SettlementCellEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE,
                    generator = "sc_seq")
    @SequenceGenerator(name = "sc_seq",
                       sequenceName = "valuation.settlement_cell_seq",
                       allocationSize = 50)       // Pattern #23
    private Long id;

    @Column(name = "cell_uuid", nullable = false, unique = true)
    private UUID cellUuid;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "position_id", nullable = false)
    private UUID positionId;      // FK to PositionLedgerEntry

    @Column(name = "interval_start", nullable = false)
    private Instant intervalStart;

    @Column(name = "interval_end", nullable = false)
    private Instant intervalEnd;

    @Column(name = "valuation_type", length = 16, nullable = false)
    private String valuationType;  // SETTLEMENT

    @Column(name = "cell_status", length = 16, nullable = false)
    private String cellStatus;     // PROVISIONAL, FINAL

    // Resolved values
    @Column(name = "price", nullable = false)            // precision/scale: NumericPrecision.PRICE (TR-047)
    private BigDecimal price;

    @Column(name = "volume_mw", nullable = false)       // precision/scale: NumericPrecision.VOLUME (TR-047)
    private BigDecimal volumeMw;

    @Column(name = "volume_mwh", nullable = false)      // precision/scale: NumericPrecision.ENERGY (TR-047)
    private BigDecimal volumeMwh;

    @Column(name = "amount", nullable = false)          // precision/scale: NumericPrecision.MONETARY (TR-047)
    private BigDecimal amount;     // price × volume_mwh (or appropriate calc)

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    // active_leaves (FR-048f) — serialized as JSON array
    @Column(name = "active_leaves", columnDefinition = "jsonb")
    private String activeLeaves;

    // input_version_set (FR-071) — serialized as JSON map
    @Column(name = "input_version_set", columnDefinition = "jsonb", nullable = false)
    private String inputVersionSet;

    // Bitemporal axes
    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    @Column(name = "known_from", nullable = false)
    private Instant knownFrom;

    @Column(name = "known_to")
    private Instant knownTo;       // null = current knowledge
}
```

**Key indexes:**

| Index | Columns | Purpose |
|-------|---------|---------|
| `idx_sc_position_interval` | `(tenant_id, position_id, interval_start)` | Cell lookup by position and time |
| `idx_sc_current_knowledge` | `(tenant_id, position_id, known_to)` | Current-state filter: `WHERE known_to IS NULL` |
| `idx_sc_bitemporal` | `(tenant_id, valid_from, valid_to, known_from, known_to)` | As-of reconstruction |

### 11.2 Forward Marks (S5b) — FR-075, FR-076, D-3, S5b

> **TR-022** — Forward marks are ephemeral current-state only. No bitemporal versioning, no durable persistence. Implemented as in-memory or short-TTL cached values. Overwritten on every revaluation cycle. "What was the intraday mark at 14:32?" is explicitly not an answerable query, by design (FR-076). (Extends FR-075, D-3.)

```java
/**
 * Port interface for forward mark storage. Lives in pv-domain/port/.
 * Ephemeral — no history, no bitemporality.
 * FR-075: current state only, overwritten on revaluation.
 */
public interface ForwardMarkStore {

    /**
     * Write or overwrite a forward mark for a position × interval.
     * FR-075: current value only; prior value discarded.
     */
    void put(String tenantId, UUID positionId,
             Instant intervalStart, Instant intervalEnd,
             BigDecimal markValue, String currency,
             Map<String, Long> inputVersionSet);

    /**
     * Read current forward mark.
     * Returns Optional.empty() if no mark exists (FR-075a: awaiting first curve tick).
     */
    Optional<ForwardMark> get(String tenantId, UUID positionId,
                               Instant intervalStart);

    /**
     * Bulk read for a position across an interval range.
     * Used for grid display and portfolio aggregation.
     */
    List<ForwardMark> getRange(String tenantId, UUID positionId,
                                Instant rangeStart, Instant rangeEnd);

    /**
     * Remove all marks for a position (on cancellation).
     */
    void removeAll(String tenantId, UUID positionId);
}

/**
 * Forward mark value record. Ephemeral, no version tracking.
 */
public record ForwardMark(
    UUID positionId,
    Instant intervalStart,
    Instant intervalEnd,
    BigDecimal markValue,
    String currency,
    Map<String, Long> inputVersionSet
) {}
```

**Implementation options** (decision deferred to adapter layer):

| Option | Pros | Cons |
|--------|------|------|
| Redis with short TTL (5 min) | Fast reads for grid, survives JVM restart | Extra infra; TTL requires careful invalidation |
| In-memory `ConcurrentHashMap` | Simplest; no infra dependency | Lost on restart; memory pressure for large portfolios |

**Decision:** Use Redis via the `VolumeCache` port (§12) with a dedicated key namespace `fwd:{tenant_id}:{position_id}:{interval_start_iso}`. TTL = 5 minutes. On cache miss after restart, the next batch cycle (FR-105) repopulates. Rationale: grid responsiveness matters; the data is small per position and already requires Redis for S6.

### 11.3 EOD Struck Marks (S5c) — Pattern #34, FR-077, FR-078, FR-079, D-3, S5c

> **TR-023** — EOD struck marks are durable immutable records. Grain: position × delivery-month bucket × business day (FR-078). Immutable — a mis-strike is corrected by a superseding strike record, never by editing. This is the single sanctioned stored snapshot in the model (FR-079). (Extends FR-077, D-3.)

```java
@Entity
@Table(name = "struck_mark", schema = "valuation")
public class StruckMarkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE,
                    generator = "sm_seq")
    @SequenceGenerator(name = "sm_seq",
                       sequenceName = "valuation.struck_mark_seq",
                       allocationSize = 50)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "position_id", nullable = false)
    private UUID positionId;

    @Column(name = "delivery_month", nullable = false)
    private YearMonth deliveryMonth;    // FR-078: month-bucket grain

    @Column(name = "strike_date", nullable = false)
    private LocalDate strikeDate;       // business day of the close

    @Column(name = "mark_value", nullable = false)       // precision/scale: NumericPrecision.MONETARY (TR-047)
    private BigDecimal markValue;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    // Version stamps (FR-077)
    @Column(name = "curve_version_set", columnDefinition = "jsonb", nullable = false)
    private String curveVersionSet;

    @Column(name = "fx_version", length = 64)
    private String fxVersion;

    @Column(name = "volume_version_set", columnDefinition = "jsonb")
    private String volumeVersionSet;

    @Column(name = "expression_version", nullable = false)
    private long expressionVersion;

    // Supersession (mis-strike correction)
    @Column(name = "supersedes_id")
    private Long supersedesId;          // null for original; FK for correction

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "is_restrike", nullable = false)
    private boolean isRestrike;         // true if this supersedes a prior strike
}
```

**Key indexes:**

| Index | Columns | Purpose |
|-------|---------|---------|
| `idx_sm_position_month` | `(tenant_id, position_id, delivery_month, strike_date)` | Lookup by position and period |
| `idx_sm_strike_date` | `(tenant_id, strike_date)` | Batch strike processing |
| `uq_sm_original` | `(tenant_id, position_id, delivery_month, strike_date)` | Unique original strike per day `WHERE supersedes_id IS NULL` |

### 11.4 `AbstractMaterializationJob` — Pattern #15, FR-056, FR-105, S5a, S5b, S5c

> **TR-024** — `AbstractMaterializationJob` is a template method (Pattern #15) with three hooks: `resolveVolume()`, `evaluatePrice()`, `writeResult()`. Concrete implementations for settlement (S5a), forward marks (S5b), and EOD strike (S5c) share the orchestration skeleton but differ in volume source, output destination, and temporal semantics. (Extends FR-056, FR-105.)

```java
/**
 * Template method for materialization jobs.
 * Lives in pv-domain/service/.
 * Concrete subclasses: SettlementMaterializationJob, ForwardMarkJob, EodStrikeJob.
 */
public abstract class AbstractMaterializationJob {

    protected final VolumeResolver volumeResolver;
    protected final PriceEvaluator priceEvaluator;
    protected final MarketDataPort marketData;

    protected AbstractMaterializationJob(VolumeResolver volumeResolver,
                                          PriceEvaluator priceEvaluator,
                                          MarketDataPort marketData) {
        this.volumeResolver = volumeResolver;
        this.priceEvaluator = priceEvaluator;
        this.marketData = marketData;
    }

    /**
     * Orchestration skeleton — not overridable.
     * FR-105: restartable and idempotent.
     */
    public final void execute(PositionLedgerEntry position,
                               DeliveryRange intervalRange) {
        // Step 1: resolve volume
        List<VolumeRecord> volumes = resolveVolume(position, intervalRange);

        for (VolumeRecord vol : volumes) {
            // Step 2: evaluate price expression for this interval
            DeliveryPeriod interval = DeliveryPeriod.of(
                vol.intervalStart(), vol.intervalEnd());
            PriceResolution priceRes = evaluatePrice(
                position.priceExpressionId(), interval);

            // Step 3: write result to appropriate target
            writeResult(position, vol, priceRes);
        }
    }

    /**
     * Hook: resolve volume from the appropriate source.
     * S5a: metered actuals for delivered; S5b: forecast for undelivered.
     */
    protected abstract List<VolumeRecord> resolveVolume(
        PositionLedgerEntry position, DeliveryRange intervalRange);

    /**
     * Hook: evaluate the price expression tree.
     * S5a: purpose=SETTLEMENT; S5b: purpose=FORWARD.
     */
    protected abstract PriceResolution evaluatePrice(
        UUID priceExpressionId, DeliveryPeriod interval);

    /**
     * Hook: write the materialized result.
     * S5a: persist bitemporal settlement cell; S5b: overwrite ephemeral mark;
     * S5c: persist immutable struck mark.
     */
    protected abstract void writeResult(
        PositionLedgerEntry position, VolumeRecord volume,
        PriceResolution price);
}
```

**Settlement materialization (S5a) — hook implementations excerpt:**

```java
public class SettlementMaterializationJob extends AbstractMaterializationJob {

    private final SettlementCellRepository cellRepo;
    private final DomainEventPublisher eventPublisher;

    // @Inject constructor ...

    @Override
    protected List<VolumeRecord> resolveVolume(
            PositionLedgerEntry position, DeliveryRange intervalRange) {
        // FR-051a: purpose=SETTLEMENT reads metered actuals for asset-linked trades
        VolumeReference ref = lookupReference(position);
        return volumeResolver.resolve(ref, intervalRange,
            ResolutionPurpose.SETTLEMENT);
    }

    @Override
    protected PriceResolution evaluatePrice(
            UUID priceExpressionId, DeliveryPeriod interval) {
        PriceExpression expr = loadExpression(priceExpressionId);
        // FR-048e: purpose=SETTLEMENT uses settlement series on MarketDataLeaf
        return priceEvaluator.evaluate(expr, interval,
            ResolutionPurpose.SETTLEMENT, marketData);
    }

    @Override
    protected void writeResult(PositionLedgerEntry position,
                                VolumeRecord volume,
                                PriceResolution price) {
        // FR-071: persist with active_leaves and input_version_set
        // FR-072: bitemporal — new version if inputs restated
        var cell = buildSettlementCell(position, volume, price);
        cellRepo.save(cell);
        eventPublisher.publish(new SettlementComputed(
            position.tenantId(), position.id(),
            volume.intervalStart(), volume.intervalEnd(),
            price.value(), Instant.now()));
    }
}
```

---

## §12 — S6: Slot Cache & S6b: Trade Interval Cache

### 12.1 `VolumeCache` Port — Pattern #29, #30, #31, FR-079, FR-080, S6

> **TR-025** — `VolumeCache` is a port interface in `pv-domain/port/cache/`. It abstracts the Redis-backed slot cache. The port exposes read-through (Pattern #29), event invalidation (Pattern #30), and pipeline batching (Pattern #31). Redis outage degrades latency, not correctness — reads fall through to the database reader endpoint. (Extends FR-079, FR-080.)

```java
/**
 * Port interface for the volume/position cache layer.
 * Lives in pv-domain/port/cache/.
 *
 * Three patterns:
 * - Pattern #29: Read-through (miss → DB → populate → return)
 * - Pattern #30: Cache-aside with event invalidation (VolumeSuperseded → invalidate)
 * - Pattern #31: Pipeline batching (MGET for bulk interval fetch)
 */
public interface VolumeCache {

    /**
     * Get a single cached interval value.
     * Pattern #29: on miss, reads from DB via VolumeSeriesRepository, populates cache.
     * @param readConsistent if true, bypasses cache and reads from writer endpoint
     */
    Optional<CachedInterval> get(String tenantId,
                                  String seriesKey,
                                  Instant intervalStart,
                                  boolean readConsistent);

    /**
     * Bulk get for a series across multiple interval starts.
     * Pattern #31: translates to Redis MGET for up to 2,976 keys per delivery month.
     */
    List<CachedInterval> getAll(String tenantId,
                                 String seriesKey,
                                 List<Instant> intervalStarts);

    /**
     * Write a cached interval. Used during read-through population.
     * TTL: 24 hours (V2.0 §12).
     */
    void put(String tenantId, String seriesKey,
             Instant intervalStart, CachedInterval value);

    /**
     * Bulk write. Redis MULTI-EXEC for atomic batch writes.
     */
    void putAll(String tenantId, String seriesKey,
                Map<Instant, CachedInterval> values);

    /**
     * Invalidate cached entries for a series within an affected range.
     * Pattern #30: triggered by VolumeSuperseded event.
     */
    void invalidate(String tenantId, String seriesKey,
                     DeliveryRange affectedRange);

    /**
     * Invalidate all entries for a series (trade amendment, full supersession).
     */
    void invalidateAll(String tenantId, String seriesKey);
}

/**
 * Cached interval value — netted position data for grid display.
 * FR-080: grain = (delivery_point, portfolio, position_type) × atomic interval.
 */
public record CachedInterval(
    Instant intervalStart,
    Instant intervalEnd,
    BigDecimal netMw,        // FR-081: dual-unit materialized in cache
    BigDecimal netMwh,       // FR-081: dual-unit materialized in cache
    boolean isPeak,          // FR-080: peak classification from calendar
    String calendarVersion,
    String versionHash       // FR-080: staleness detection
) {}
```

### 12.2 `RedisVolumeCache` Adapter — Pattern #29, #30, #31, V2.0 §12, S6

> **TR-026** — Redis key scheme: `vol:{tenant_id}:{series_key}:{interval_start_iso}`. TTL: 24 hours. Eviction policy: `allkeys-lru`. Active eviction via pg_cron job removes keys outside the rolling hot window (M + M+1 + M+2). Instance type: `cache.r7g.large` (13 GB usable; ~5 GB practical for ~26M active keys). (Extends V2.0 §12.)

```java
/**
 * Redis adapter implementing VolumeCache. Lives in pv-redis/.
 * Uses Lettuce (non-blocking) as the Redis client.
 *
 * Key scheme: vol:{tenant_id}:{series_key}:{interval_start_iso}
 * Example: vol:TN_0042:FCST-WP-NORDSEE:2026-08-15T14:00:00Z
 */
public class RedisVolumeCache implements VolumeCache {

    private static final String KEY_PREFIX = "vol:";
    private static final Duration TTL = Duration.ofHours(24);

    private final RedisCommands<String, byte[]> redis;
    private final VolumeSeriesRepository fallbackRepo;  // for read-through

    @Inject
    public RedisVolumeCache(RedisCommands<String, byte[]> redis,
                             VolumeSeriesRepository fallbackRepo) {
        this.redis = redis;
        this.fallbackRepo = fallbackRepo;
    }

    @Override
    public List<CachedInterval> getAll(String tenantId,
                                        String seriesKey,
                                        List<Instant> intervalStarts) {
        // Pattern #31: MGET for bulk fetch
        String[] keys = intervalStarts.stream()
            .map(start -> buildKey(tenantId, seriesKey, start))
            .toArray(String[]::new);

        List<KeyValue<String, byte[]>> results = redis.mget(keys);

        // Identify misses for read-through (Pattern #29)
        var hits = new ArrayList<CachedInterval>();
        var misses = new ArrayList<Instant>();

        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).hasValue()) {
                hits.add(deserialize(results.get(i).getValue()));
            } else {
                misses.add(intervalStarts.get(i));
            }
        }

        // Read-through for misses
        if (!misses.isEmpty()) {
            var fromDb = fallbackRepo.findCurrentBySeriesKey(tenantId, seriesKey);
            // ... populate cache, add to hits
        }

        return hits;
    }

    @Override
    public void invalidate(String tenantId, String seriesKey,
                            DeliveryRange affectedRange) {
        // Pattern #30: delete affected keys
        // Build key pattern for scan: vol:{tenant}:{series}:*
        // Filter by interval_start within affectedRange
        String pattern = KEY_PREFIX + tenantId + ":" + seriesKey + ":*";
        var cursor = redis.scan(ScanArgs.Builder.matches(pattern).limit(1000));
        // ... filter and delete keys within range
    }

    private String buildKey(String tenantId, String seriesKey, Instant start) {
        return KEY_PREFIX + tenantId + ":" + seriesKey + ":" + start.toString();
    }

    // ... serialize/deserialize helpers
}
```

### 12.3 Invalidation Event Handlers — Pattern #30, FR-052b, S6

> **TR-027** — Cache invalidation is event-driven and triggered after transaction commit. Three invalidation paths: (1) `VolumeSuperseded` → invalidate affected `series_key` × `affected_range`; (2) `VolumeReference` changed → invalidate old and new `volume_series_key`; (3) trade amendment → invalidate trade's PROFILE `series_key`. All invalidation is idempotent. (Extends FR-052b, V2.0 §12.5.)

```java
/**
 * Domain service that handles volume supersession events for cache invalidation.
 * Lives in pv-domain/service/ — depends on VolumeCache port, not Redis directly.
 */
public class CacheInvalidationHandler {

    private final VolumeCache cache;
    private final VolumeSeriesRepository seriesRepo;

    @Inject
    public CacheInvalidationHandler(VolumeCache cache,
                                     VolumeSeriesRepository seriesRepo) {
        this.cache = cache;
        this.seriesRepo = seriesRepo;
    }

    /**
     * Handle VolumeSuperseded — invalidate affected cache entries.
     * FR-052b: locate affected trade-legs, invalidate per-series cache entries.
     */
    public void onVolumeSuperseded(VolumeSuperseded event) {
        cache.invalidate(event.tenantId(), event.seriesKey(),
            new DeliveryRange(event.affectedRangeStart(),
                              event.affectedRangeEnd(),
                              ZoneId.of("UTC")));
    }

    /**
     * Handle VolumeReference change — invalidate both old and new series.
     */
    public void onVolumeReferenceChanged(String tenantId,
                                          String oldSeriesKey,
                                          String newSeriesKey) {
        cache.invalidateAll(tenantId, oldSeriesKey);
        cache.invalidateAll(tenantId, newSeriesKey);
    }
}
```

### 12.4 S6b — Trade Interval Cache — Pattern #11, D-12, FR-086, FR-086a–FR-086e, S6b

> **TR-028** — S6b stores pre-multiplied resolved volume per trade-leg per atomic interval: `resolved_qty = volume × multiplier`, `resolved_energy = energy × multiplier`. S6b is NOT source of truth — entirely rebuildable from `volume_reference → volume_series → volume_interval × multiplier`. Commodity-neutral column names (`resolved_qty`/`resolved_energy`) per D-12. (Extends FR-086, D-12.)

#### Port Interface

```java
/**
 * Port interface for the trade interval cache (S6b).
 * Lives in pv-domain/port/cache/.
 *
 * FR-086: optional, rebuildable materialization.
 * FR-086a: grain = trade_leg_id × atomic interval.
 * FR-086e: commodity-neutral resolved_qty/energy columns.
 */
public interface TradeIntervalCache {

    /**
     * Read pre-multiplied volume for a trade-leg over an interval range.
     * FR-086c: avoids runtime join for portfolio-detail dashboards.
     */
    List<TradeIntervalRecord> getForTradeLeg(String tenantId,
                                              String tradeLegId,
                                              Instant rangeStart,
                                              Instant rangeEnd);

    /**
     * Rebuild cache entries for affected trade-leg × interval range.
     * FR-086b: triggered by VolumeSuperseded, VolumeReference change, trade amendment.
     * Idempotent: re-derive from source per FR-106.
     */
    void rebuild(String tenantId, String tradeLegId,
                  DeliveryRange affectedRange);

    /**
     * Bulk write pre-multiplied entries.
     * Used during initial population and rebuild.
     */
    void writeAll(String tenantId, List<TradeIntervalRecord> records);
}

/**
 * Pre-multiplied trade interval record.
 * FR-086a: content per cell.
 */
public record TradeIntervalRecord(
    String tradeLegId,
    Instant intervalStart,
    Instant intervalEnd,
    BigDecimal resolvedQty,       // volume × multiplier (D-12: commodity-neutral)
    BigDecimal resolvedEnergy,    // energy × multiplier (D-12: commodity-neutral)
    BigDecimal multiplier,        // snapshot for auditability
    String seriesKey,             // which volume series was used
    String versionHash            // staleness detection
) {}
```

#### JPA Entity Skeleton (`pv-persistence`)

The DDL for `volume_series.trade_interval_cache` is defined in V2.0 §5. The JPA entity:

```java
@Entity
@Table(name = "trade_interval_cache", schema = "volume_series")
public class TradeIntervalCacheEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE,
                    generator = "tic_seq")
    @SequenceGenerator(name = "tic_seq",
                       sequenceName = "volume_series.trade_interval_cache_seq",
                       allocationSize = 50)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "trade_leg_id", length = 64, nullable = false)
    private String tradeLegId;

    @Column(name = "interval_start", nullable = false)
    private Instant intervalStart;

    @Column(name = "interval_end", nullable = false)
    private Instant intervalEnd;

    @Column(name = "resolved_qty", nullable = false)     // precision/scale: NumericPrecision.VOLUME (TR-047)
    private BigDecimal resolvedQty;

    @Column(name = "resolved_energy", nullable = false)  // precision/scale: NumericPrecision.ENERGY (TR-047)
    private BigDecimal resolvedEnergy;

    @Column(name = "multiplier", nullable = false)       // precision/scale: NumericPrecision.MULTIPLIER (TR-047)
    private BigDecimal multiplier;

    @Column(name = "series_key", length = 128, nullable = false)
    private String seriesKey;

    @Column(name = "version_hash", length = 64, nullable = false)
    private String versionHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
```

**Key indexes** (V2.0 §7):

| Index | Columns | Purpose |
|-------|---------|---------|
| `idx_tic_trade_leg_time` | `(trade_leg_id, interval_start)` | Primary lookup for trade-level detail |
| `idx_tic_tenant_time` | `(tenant_id, interval_start)` | Cross-trade time-range queries |

#### Materialization Strategy Dispatch (S6b)

S6b materialization is triggered by the same events as S6 (Pattern #11):

| Trigger event | S6b action |
|---------------|-----------|
| `VolumePublished` | Initial population: resolve volume × multiplier for all intervals |
| `VolumeSuperseded` | Rebuild affected intervals with new volume data |
| `VolumeReference` changed | Rebuild with new multiplier / series pointer |
| Trade amendment | Rebuild affected trade-leg's intervals |

S6b uses `BatchWriter` (Pattern #20) for writes and virtual threads for parallel chunk processing of multi-month rebuilds:

```java
/**
 * S6b rebuild logic. Uses virtual threads for parallel chunk processing.
 * Lives in pv-domain/service/.
 */
public class TradeIntervalCacheRebuilder {

    private final TradeIntervalCache cache;
    private final VolumeResolver resolver;
    private final VolumeSeriesRepository seriesRepo;

    @Inject
    public TradeIntervalCacheRebuilder(TradeIntervalCache cache,
                                        VolumeResolver resolver,
                                        VolumeSeriesRepository seriesRepo) {
        this.cache = cache;
        this.resolver = resolver;
        this.seriesRepo = seriesRepo;
    }

    /**
     * Rebuild S6b for a trade-leg across all delivery months.
     * Uses virtual threads for I/O-bound parallel chunk processing.
     */
    public void rebuildForTradeLeg(String tenantId,
                                    VolumeReference ref,
                                    DeliveryRange fullRange) {
        List<YearMonth> months = fullRange.toMonths();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = months.stream()
                .map(month -> executor.submit(() -> {
                    DeliveryRange monthRange = DeliveryRange.forMonth(
                        month, fullRange.timezone());
                    List<VolumeRecord> volumes = resolver.resolve(
                        ref, monthRange, ResolutionPurpose.FORWARD);
                    List<TradeIntervalRecord> records = volumes.stream()
                        .map(v -> new TradeIntervalRecord(
                            ref.tradeLegId(),
                            v.intervalStart(), v.intervalEnd(),
                            v.volumeMw(), v.volumeMwh(),
                            ref.multiplier(), ref.volumeSeriesKey(),
                            computeVersionHash(v)))
                        .toList();
                    cache.writeAll(tenantId, records);
                }))
                .toList();

            // Await all chunks
            for (Future<?> f : futures) {
                f.get();
            }
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            throw new MaterializationException(
                "S6b rebuild failed for trade-leg " + ref.tradeLegId(), e);
        }
    }
}
```

---

## §13 — S7: Rollups & S8: Dependency Index

### 13.1 S7 — Rollups — FR-090, FR-091, S7

> **TR-029** — Rollups are rebuildable, coarse-grain materialized aggregates serving far-dated grid views and regulatory extracts. Grain: hourly / daily / monthly / delivery-year. Rollups inherit the netting-is-projection rule (FR-082). Refreshed on the batch cycle (FR-105 step 4). (Extends FR-090, FR-091.)

#### Rollup Query Interfaces

```java
/**
 * Port interface for rollup queries. Lives in pv-domain/port/repository/.
 * Rollups serve grid requests beyond the hot window and regulatory extracts.
 */
public interface RollupRepository {

    /**
     * Read rollup cells for a tenant within a delivery range at a given granularity.
     * FR-090: hourly / daily / monthly / delivery-year.
     */
    List<RollupCell> findByRange(String tenantId,
                                  String deliveryPointId,
                                  String portfolioId,
                                  Instant rangeStart,
                                  Instant rangeEnd,
                                  TimeGranularity granularity);

    /**
     * Refresh rollup cells from source data (slot cache + settlement cells).
     * FR-105 step 4: called during batch cycle.
     * FR-091: rebuildable, versioned against calendar version.
     */
    void refresh(String tenantId,
                  Instant rangeStart,
                  Instant rangeEnd,
                  TimeGranularity granularity);
}

/**
 * Materialized rollup cell.
 * FR-090: per (delivery_point, portfolio, position_type) × period × peak/off-peak.
 */
public record RollupCell(
    Instant periodStart,
    Instant periodEnd,
    TimeGranularity granularity,
    String deliveryPointId,
    String portfolioId,
    boolean isPeak,
    BigDecimal netMw,              // time-weighted average MW (FR-085, FR-090)
    BigDecimal netMwh,             // sum of MWh (FR-090)
    BigDecimal settledValue,       // sum of settlement cell amounts (S5a) — nullable
    BigDecimal forwardMarkValue,   // sum of forward mark amounts (S5b) — nullable
    String currency,
    String calendarVersion,
    String versionHash
) {}
```

#### Functional-Interface Aggregators

```java
/**
 * Aggregation functions for rollup computation.
 * FR-085: granularity conversion rules.
 * FR-090: net_mw = time-weighted average; net_mwh = sum.
 */
@FunctionalInterface
public interface IntervalAggregator<T> {
    T aggregate(List<CachedInterval> sourceIntervals);
}

// Concrete aggregators (static factories)
public final class Aggregators {

    /**
     * Time-weighted average MW.
     * FR-085: weights = interval minutes; mandatory for DST-correct aggregation.
     */
    /** NumericPrecision injected — scale resolved per domain (TR-048). */
    public static IntervalAggregator<BigDecimal> timeWeightedMw(NumericPrecision np) {
        return intervals -> {
            BigDecimal weightedSum = BigDecimal.ZERO;
            long totalMinutes = 0;
            for (CachedInterval i : intervals) {
                long minutes = Duration.between(i.intervalStart(), i.intervalEnd())
                    .toMinutes();
                weightedSum = weightedSum.add(
                    i.netMw().multiply(BigDecimal.valueOf(minutes)));
                totalMinutes += minutes;
            }
            return totalMinutes == 0 ? BigDecimal.ZERO
                : weightedSum.divide(BigDecimal.valueOf(totalMinutes),
                    np.scale(NumericPrecision.Domain.VOLUME), np.roundingMode());
        };
    }

    /**
     * Sum of MWh. FR-085/FR-090: MWh sums on roll-up.
     */
    public static IntervalAggregator<BigDecimal> sumMwh() {
        return intervals -> intervals.stream()
            .map(CachedInterval::netMwh)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Aggregators() {}
}
```

### 13.2 S8 — Dependency Index — FR-102, FR-103, FR-104, S8

> **TR-030** — The dependency index maps (input series key → valuation cell) with `active_leaves` membership per edge. An input publication/restatement locates affected cells by index lookup — never by scanning the valuation store. Edges are pruned by class once irrelevant (FR-104): forward-curve edges drop on settlement; settlement-input edges persist until the delivery month leaves the hot store. (Extends FR-102, FR-103, FR-104.)

```java
/**
 * Port interface for the dependency index (S8).
 * Lives in pv-domain/port/repository/.
 *
 * FR-102: reverse-dependency edges for incremental revaluation.
 * FR-103: active_leaves filtering for blast-radius optimization.
 * FR-104: lifecycle pruning.
 */
public interface DependencyIndex {

    /**
     * Upsert a dependency edge: cell depends on input series.
     * FR-102: every resolution upserts edges with active_leaves.
     */
    void upsert(DependencyEdge edge);

    /**
     * Find all cells affected by an input series change within a reference range.
     * FR-102: index lookup, never a valuation-store scan.
     * @param activeLeafFilter if non-null, return only edges where this leaf is active
     *        (FR-103: blast-radius optimization)
     */
    List<DependencyEdge> findAffectedCells(String tenantId,
                                            String inputSeriesKey,
                                            DeliveryRange affectedRange,
                                            String activeLeafFilter);

    /**
     * Prune edges that are no longer relevant.
     * FR-104: forward-curve edges drop on settlement handover;
     *         settlement-input edges persist until delivery month leaves hot store.
     */
    void prune(String tenantId, PrunePolicy policy);
}

/**
 * A reverse-dependency edge: cell X depends on input series Y.
 * Carries active_leaves for blast-radius optimization (FR-103).
 */
public record DependencyEdge(
    String tenantId,
    UUID cellId,                  // valuation cell (settlement or forward mark)
    String cellType,              // SETTLEMENT, FORWARD, EOD_STRUCK
    String inputSeriesKey,        // market data or volume series key
    String inputType,             // PRICE, VOLUME, FX, INDEX
    DeliveryRange affectedRange,  // interval range this edge covers
    Set<String> activeLeaves,     // FR-048f: which leaves were active at resolution
    Instant createdAt,
    Instant prunedAt              // null = active; set when edge is pruned (FR-104)
) {}

/**
 * Pruning policy for dependency edge lifecycle management.
 * FR-104: different rules for different edge classes.
 */
public sealed interface PrunePolicy
    permits SettlementHandoverPolicy, HotStoreRetentionPolicy {
}

/**
 * Prune forward-curve edges when interval settles final.
 * FR-104: forward-curve edges drop on settlement handover.
 */
public record SettlementHandoverPolicy(
    Instant settlementCutoff
) implements PrunePolicy {}

/**
 * Prune settlement-input edges when delivery month leaves hot store.
 * FR-104: persist until retention window expires.
 */
public record HotStoreRetentionPolicy(
    YearMonth oldestRetainedMonth
) implements PrunePolicy {}
```

#### Dependency Topology — What Invalidates What

| Input event | Source | Affected subsystems | Index lookup |
|-------------|--------|---------------------|-------------|
| `VolumeSuperseded` (FORECAST) | S3 | S5b (forward marks), S6 (slot cache), S6b (trade interval cache) | `findAffectedCells(seriesKey, VOLUME, affectedRange)` |
| `VolumeSuperseded` (METERED_ACTUAL) | S3 | S5a (settlement cells), S6b (trade interval cache) | `findAffectedCells(seriesKey, VOLUME, affectedRange)` |
| `SettlementPublished` / `CurveTick` | S4 | S5a (settlement cells), S5b (forward marks), S5c (EOD struck) | `findAffectedCells(series, PRICE, affectedRange)` |
| `IndexRestated` (e.g., HICP) | S4 | S5a (settlement cells where CPI ∈ `active_leaves`) | `findAffectedCells(series, INDEX, affectedRange, "CPI")` |
| `FxPublished` | S4 | S5a/S5b (cells with FxConvert in expression) | `findAffectedCells(series, FX, affectedRange)` |
| `PositionCaptured` / `PositionAmended` | S1 | S5a, S5b, S6, S6b, S7 (full cascade) | Create new edges for the new position version |

> **TR-031** — The blast-radius optimization (FR-103) applies to both price and volume inputs. For the reference deal (collar PPA): HICP restatement only recomputes cells where CPI ∈ `active_leaves` (floor/cap binding). Inside-collar cells (CPI inactive) are provably unaffected and are not rewritten (FR-074). The same logic applies to volume inputs: meter supersession only recomputes cells where METER ∈ `active_leaves` — gated intervals (DA < 0, amount = 0) where the meter is irrelevant are skipped (FR-057a). (Extends FR-103, FR-057a.)

---

*End of Part 2. Continue to [Part 3 — Cross-cutting and Wiring (§14–§22)](TECH-SPEC-position-valuation-library_guice-v1.0-part3-crosscutting-and-wiring.md).*
