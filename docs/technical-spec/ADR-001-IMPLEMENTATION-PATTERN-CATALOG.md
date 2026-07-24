# ADR-001: Implementation Pattern Catalog for Position & Valuation System

| Field | Value |
|---|---|
| **Status** | Proposed |
| **Date** | 2026-07-24 |
| **Deciders** | Architecture team |
| **Companion Specs** | `functional-spec-position-valuation-v1.0.md`, `VOLUME_SERIES_SPEC-V3_0.md`, `VOLUME_SERIES_DATA_ARCHITECTURE-V2_0.md` |

---

## 1. Context

The Position & Valuation system is a multitenant SaaS platform for EU power CTRM (~200 tenants) that must handle bitemporal position tracking, formula-based pricing (PPAs with collars, escalation, negative-price gates), unified volume resolution via `VolumeReference × multiplier`, event-driven revaluation, and regulatory-grade auditability (REMIT, EMIR, MiFID II).

The system comprises eight subsystems (S1–S8 plus S6b) spanning domain entities, derived measures, caches, and integration boundaries. The functional spec (V1.0) fixes twelve design decisions (D-1 through D-12) and defines ~120 functional rules (FR-nnn). The volume domain spec (V3.0) introduces the unified model. The data architecture (V2.0) specifies the persistence layer on Aurora PostgreSQL 16.

**Why this ADR:** As we move from specification to Java 21 implementation, we need a single reference mapping each implementation pattern to specific spec requirements, subsystems, and Java 21 language features. This catalog bridges spec and code — every pattern traces to at least one FR-nnn or D-nn reference, and every subsystem is covered.

**Platform:** Java 21 / Spring Boot 3.3 / Aurora PostgreSQL 16 / Kafka 3.7 (KRaft) / Redis 7 / pg_partman + pg_cron / Flyway / HikariCP + PgBouncer.

---

## 2. Decision: Pattern Catalog

### 2.1 Category 1 — Domain Model Patterns (5 patterns)

| # | Pattern | Where Applied | Spec Reference | Java 21 Feature |
|---|---|---|---|---|
| 1 | Entity vs Measure distinction | `PositionLedgerEntry` is an entity (lifecycle-bearing, bitemporal); `SettlementCell`, slot cache entries, forward marks are measures (coordinate-identified, derived, rebuildable) | D-1, D-8, FR-001–FR-003 | — |
| 2 | Aggregate Root | `VolumeSeries` (root) → `VolumeInterval` (child, ordered by `intervalStart`); `MeteredActualVolumeSeries` → `MeteredActualInterval`; `PositionLedgerEntry` as standalone root | V3.0 §3.1, §3.3.1, FR-050 | `SequencedCollection` for ordered children |
| 3 | Value Object (Java `record`) | `DeliveryPeriod`, `TimeGranularity`, `SeriesKey`, `Money`, `TimeRange`, `DeliveryRange` — immutable, equality by value, validated at construction | FR-001, FR-036, D-2, V3.0 §3.2 | `record` types |
| 4 | Enum with behavior | `QualityState` with allowed transition guards (PROVISIONAL→VALIDATED→FINAL for metered; EFFECTIVE→AMENDED for profile; CURRENT→SUPERSEDED for forecast); `SeriesType`, `VolumeUnit` with `isFixedDuration()`, `isSubDaily()` | FR-054, V3.0 §3.2.4–§3.2.6 | Enhanced `enum` with methods |
| 5 | Sealed type hierarchy | `PriceExpression` sealed interface: `Fixed`, `Index`, `Formula`, `Spread`, `Composite` — every leg carries a `price_expression_ref` (D-2); fixed price is the degenerate single-constant expression | D-2, FR-040, FR-020–FR-025 | `sealed interface … permits` + pattern matching `switch` |

### 2.2 Category 2 — Creational Patterns (3 patterns)

| # | Pattern | Where Applied | Spec Reference | Java 21 Feature |
|---|---|---|---|---|
| 6 | Builder | `PositionLedgerEntry.builder()` — complex construction with bitemporal axes (`validFrom/To`, `knownFrom/To`), signed quantity, delivery range, price expression ref; `VolumeReference.builder()` — multiplier, effective dates, series keys | D-1, D-11, FR-030–FR-036 | — |
| 7 | Factory Method | `VolumeSeriesFactory.createForTrade()` — routes to PROFILE (per-trade, `multiplier=1.0`) vs FORECAST (per-asset, shared) based on trade type; both return `VolumeSeries` but with different ownership and lifecycle characteristics | D-11, FR-050, V3.0 §2.3 | — |
| 8 | Static Factory | `Money.of(amount, currency)`, `DeliveryPeriod.of(start, end, zone)`, `SeriesKey.of(prefix, id)` — validation at construction (fail-fast), consistent naming, no invalid instances | D-2, FR-036, V3.0 §3.2 | `record` with static factory returning `this` type |

### 2.3 Category 3 — Structural Patterns (5 patterns)

| # | Pattern | Where Applied | Spec Reference | Java 21 Feature |
|---|---|---|---|---|
| 9 | Strategy — Volume Resolution | `VolumeResolver` interface with `ProfileResolver` (per-trade PROFILE series, `multiplier=1.0`) and `ForecastResolver` (shared asset FORECAST series, `multiplier < 1.0`). In practice, one code path — the "strategy" is the data properties, not branching code (D-11: no category branching) | D-11, FR-050–FR-051, V3.0 §2.3 | `sealed interface` |
| 10 | Strategy — Price Evaluation | `PriceEvaluator` interface: `FixedEvaluator` (constant), `IndexEvaluator` (market data lookup), `FormulaEvaluator` (tree walk with collar, neg-price gate, HICP escalation, FX). Expression tree nodes dispatch to evaluators | D-2, FR-020, FR-040, §6.4 | Pattern matching `switch` over `sealed` types |
| 11 | Strategy — Materialization | `MaterializationStrategy` interface: `EagerStrategy` (DA fills, short blocks — full materialization), `RollingHorizonStrategy` (long-tenor PPAs — 3 months ahead, monthly extension), `ChunkStrategy` (Kafka message per month chunk) | FR-056, V3.0 §4.1–§4.3, S6b | — |
| 12 | Composite | `PriceExpression` tree — recursive `evaluate(interval, marketData)`. Collar wraps sub-expression; escalation multiplies sub-expression by HICP factor; neg-price gate overrides floor; FX converts. Clause precedence encoded per expression (contract law), not hard-coded | D-2, FR-025, §6.2, §2.9 | Pattern matching for tree traversal |
| 13 | Decorator / Filter Chain | `TenantContextFilter` → `AuditFilter` → `RequestHandler`. Tenant filter sets `app.tenant_id` session variable (P1, V2.0 §11.1); audit filter logs bitemporal mutation context; request handler proceeds | O-2, FR-075, V2.0 §11, P1 | — |

### 2.4 Category 4 — Behavioral Patterns (4 patterns)

| # | Pattern | Where Applied | Spec Reference | Java 21 Feature |
|---|---|---|---|---|
| 14 | Observer / Domain Events | `VolumePublished` (new series version), `VolumeSuperseded` (version replaced — primary revaluation trigger; `event_time` becomes `known_from` on valuation cells), `VolumeChunkMaterialized`, `SettlementComputed`. Events carry `series_key + version_id` for downstream stamping | V3.0 §8.1–§8.3, FR-052a–FR-052c, V2.0 §13.2 | `record` for event payloads |
| 15 | Template Method | `AbstractMaterializationJob` with hooks: `resolveVolume()` (read series × multiplier), `evaluatePrice()` (walk expression tree), `writeResult()` (persist settlement cell or cache entry). Concrete implementations for settlement (S5a), forward marks (S5b), slot cache (S6), trade interval cache (S6b) | FR-056, S5a/S5b/S6/S6b | — |
| 16 | State Machine | `QualityState` transitions: PROFILE series (EFFECTIVE→AMENDED), FORECAST series (CURRENT→SUPERSEDED), metered intervals (PROVISIONAL→VALIDATED, PROVISIONAL→ESTIMATED, ESTIMATED→VALIDATED). Guard conditions prevent illegal transitions (e.g., AMENDED→EFFECTIVE) | FR-054, V3.0 §3.2.6, P2 | Enum with `transitionTo(target)` method + guard |
| 17 | Command | `TradeCapture` (create VolumeReference + PROFILE series + ledger entries), `TradeAmend` (new PROFILE version, mark old AMENDED, update VolumeReference), `TradeCancel` (mark PROFILE AMENDED terminal, soft-delete VolumeReference, close ledger valid_time). Each encapsulates a full transactional unit | FR-001–FR-005, FR-037–FR-038, V2.0 §13.1 | `record` for command payloads |

### 2.5 Category 5 — Persistence Patterns (6 patterns)

| # | Pattern | Where Applied | Spec Reference | Java 21 Feature |
|---|---|---|---|---|
| 18 | Repository | `VolumeSeriesRepository`, `PositionLedgerRepository`, `SettlementCellRepository` — Spring Data JPA interfaces; each scoped to one aggregate root. All queries include `tenant_id` as mandatory filter | V2.0 §5, P1, V3.0 §7.1 | — |
| 19 | Specification (query object) | `VolumeSeriesSpec.byTenantAndAsset(tenantId, assetId)`, `.bySeriesKeyAndVersion(key, versionId)`, `.currentVersionOnly()` — composable JPA Specifications for the unified query interface `queryVolumeForTradeLeg(tradeLegId, purpose, intervalRange)` | FR-051, V3.0 §7.1, V2.0 §7 | — |
| 20 | Unit of Work (batch flush) | Hibernate batch insert with `hibernate.jdbc.batch_size=50`, periodic `flush()` + `clear()` every 50 entities. Critical for volume interval inserts (2,976 rows per monthly chunk; 35,040 for a full-year DA profile) | V2.0 §17.1–§17.2, P3 | — |
| 21 | Identity Map | JPA first-level cache within transaction boundary — prevents re-reads of `VolumeSeries` header during bulk interval processing. Cleared after each batch flush to prevent memory pressure | V2.0 §12, §17.2 | — |
| 22 | Dual DataSource | Writer pool (Aurora primary instance, `db.r7g.2xlarge`) for all INSERT/UPDATE/DELETE; Reader pool (Aurora replicas, load-balanced `db.r7g.xlarge`) for read-heavy queries. Two `HikariCP` pools routed at `DataSource` bean level, not via `@Transactional(readOnly)` annotation (unreliable routing in some Spring configurations) | V2.0 §3.4, P4 | — |
| 23 | Sequence-based ID generation | `GenerationType.SEQUENCE` with `allocationSize=50` on all entity IDs. `IDENTITY` columns prohibited — they require per-row DB round-trips, killing Hibernate JDBC batch inserts. Pre-allocated sequence blocks enable batch-size=50 inserts with zero per-row round-trips | V2.0 §5.1, P3 | — |

### 2.6 Category 6 — Integration Patterns (5 patterns)

| # | Pattern | Where Applied | Spec Reference | Java 21 Feature |
|---|---|---|---|---|
| 24 | Transactional Outbox | `trade.outbox` table (aggregate_type, aggregate_id, event_type, JSONB payload, published_at). Relay process polls unpublished rows → publishes to Kafka with at-least-once delivery → marks `published_at` after broker ACK. Decouples domain transaction from Kafka availability | V2.0 §13.4, D-9, FR-106 | — |
| 25 | Saga (choreography) | `trade.saga_state` table tracking multi-step sequences: `trade.captured` → VolumeReference created → VolumeSeries created → Cache invalidated. Stuck sagas (`STARTED` > 30 min) surfaced to ops. Choreography-based (event-driven), not orchestrator-based | V2.0 §13.5, FR-001, D-11 | — |
| 26 | Idempotent Consumer | Natural idempotency key: `(trade_id, trade_version)`. Consumer checks `volumeReferenceRepository.existsByTradeIdAndTradeVersion()` before processing. At-least-once Kafka delivery (manual commit) with idempotent effect (re-derive-from-source, not delta-apply — D-7) | V2.0 §13.3, FR-106, D-7 | — |
| 27 | Event-Carried State Transfer | `VolumePublished` and `VolumeSuperseded` carry `series_key + version_id + delivery_range + quality_state` — sufficient for downstream consumers to identify what changed and query the relevant data. No need to fetch event source for routing decisions | V3.0 §8.1–§8.2, FR-052a | `record` for event payloads |
| 28 | CQRS (implicit) | Write path: `PositionLedgerEntry` inserts (S1), `VolumeSeries` version creation (S3), `SettlementCell` writes (S5a) — all via writer endpoint. Read path: slot cache (S6) / trade interval cache (S6b) lookups, Redis-cached volume data — all via reader endpoint. Separation enforced by dual DataSource (P4), not by separate data stores | D-1, S6, S6b, P4, V2.0 §3.4 | — |

### 2.7 Category 7 — Caching Patterns (3 patterns)

| # | Pattern | Where Applied | Spec Reference | Java 21 Feature |
|---|---|---|---|---|
| 29 | Read-Through | Redis read-through for hot-window volume data (M + M+1 + M+2, ~90 days). On cache miss → query Aurora reader endpoint → populate Redis → return. Redis outage degrades latency, not correctness. `read_consistent=true` flag bypasses Redis and routes to writer endpoint for strong consistency | V2.0 §12.6, FR-079 | — |
| 30 | Cache-Aside with Event Invalidation | `VolumeSuperseded` → invalidate Redis keys by `series_key` within `affected_range`. `VolumeReference` change → invalidate old and new `volume_series_key`. Trade amendment → invalidate PROFILE `series_key`. Key format: `vol:{tenant_id}:{series_key}:{interval_start_iso}` | V2.0 §12.5, §12.2 | — |
| 31 | Pipeline Batching | Redis `MGET` for bulk interval fetch within a delivery month (up to 2,976 keys for 15-min monthly granularity). Reduces round-trips from O(intervals) to O(1) for grid display. Redis 7 `MULTI-EXEC` for atomic batch writes after materialization | FR-079, V2.0 §14.2, SLA §14 | — |

### 2.8 Category 8 — Cross-Cutting Patterns (4 patterns)

| # | Pattern | Where Applied | Spec Reference | Java 21 Feature |
|---|---|---|---|---|
| 32 | `@TenantAware` AOP Aspect | Spring AOP aspect intercepts every request → sets `SET app.tenant_id = :tenantId` on the JDBC session → RLS policies filter all domain tables by `current_setting('app.tenant_id')`. Three-layer isolation: aspect (app) → RLS (DB) → index leading key (query). Fail-closed: unset variable matches zero rows | V2.0 §11.1–§11.2, P1, O-2 | — |
| 33 | Functional Interfaces | `Function<VolumeInterval, BigDecimal>` for multiplier application: `interval -> interval.getVolume().multiply(reference.getMultiplier())`. `BiFunction<PriceExpression, MarketDataSnapshot, BigDecimal>` for price evaluation. Used in stream pipelines for volume resolution and valuation computation | D-11, FR-051, V3.0 §2.3 | Functional interfaces + stream API |
| 34 | Immutability Enforcement | PROFILE series: no UPDATE/DELETE after insert — enforced by DB trigger (V2.0 §5.14) AND application-level check. METERED_ACTUAL: append-only with `supersedes_id` forward-link — corrections insert new rows, originals untouched. Position ledger: append-only bitemporal versioning (close `known_to`, append new version) | P2, FR-050, FR-006, V2.0 §5.14 | `record` types enforce field-level immutability |
| 35 | Bitemporal Audit | `series_history` / `position_history` audit tables with `valid_time` + `transaction_time` axes. Trigger-based capture on `volume_series` mutations (V2.0 §10.3). As-of reconstruction via `known_from ≤ K < known_to AND valid_from ≤ B < valid_to` — query-time, never stored as snapshot (except S5c EOD struck mark) | D-1, FR-006–FR-009, V2.0 §10 | — |

---

## 3. Java 21 Feature Utilization

| # | Java 21 Feature | Application | Code Example |
|---|---|---|---|
| 1 | Records | All value objects: `DeliveryPeriod`, `SeriesKey`, `Money`, `TimeRange`, `DeliveryRange`. Event payloads: `VolumePublished`, `VolumeSuperseded`. Command payloads: `TradeCapture`, `TradeAmend` | `record DeliveryPeriod(ZonedDateTime start, ZonedDateTime end, ZoneId zone) { }` |
| 2 | Sealed interfaces | `PriceExpression` hierarchy (5 subtypes), `VolumeResolutionStrategy`, `MaterializationStrategy`. Compiler-enforced exhaustive matching in `switch` | `sealed interface PriceExpression permits Fixed, Index, Formula, Spread, Composite { }` |
| 3 | Pattern matching (`switch`) | PriceExpression tree walker: dispatch evaluation per node type. Event routing: dispatch handler per event type. QualityState transitions | `case Fixed f -> f.price(); case Index i -> lookupMarketData(i); case Formula f -> evaluateTree(f);` |
| 4 | Text blocks | JPQL queries, SQL fragments for complex bitemporal filters, Flyway migration SQL, outbox payload templates | `String jpql = """ SELECT v FROM VolumeSeries v WHERE v.seriesKey = :key AND v.qualityState = 'CURRENT' """;` |
| 5 | Virtual threads (future consideration) | I/O-bound chunk materialization workers — each chunk (one delivery month, ~2,976 intervals) is independent. Virtual threads enable high-concurrency materialization without thread-pool tuning | `try (var executor = Executors.newVirtualThreadPerTaskExecutor()) { chunks.forEach(c -> executor.submit(() -> materialize(c))); }` |
| 6 | `SequencedCollection` | Ordered interval lists on `VolumeSeries.intervals` (ordered by `intervalStart`), version chains on audit history (`getFirst()` / `getLast()` for boundary access) | `SequencedSet<VolumeInterval> intervals = new TreeSet<>(Comparator.comparing(VolumeInterval::intervalStart));` |

---

## 4. Summary Table

All 35 patterns in a single reference view:

| # | Pattern | Category | Subsystem(s) | Spec Reference | Java 21 Feature |
|---|---|---|---|---|---|
| 1 | Entity vs Measure distinction | Domain Model | S1, S5a, S5b, S6 | D-1, D-8, FR-001–FR-003 | — |
| 2 | Aggregate Root | Domain Model | S3, S1 | V3.0 §3.1, FR-050 | `SequencedCollection` |
| 3 | Value Object (record) | Domain Model | S1, S2, S3 | FR-001, FR-036, D-2 | `record` |
| 4 | Enum with behavior | Domain Model | S3, S5a | FR-054, V3.0 §3.2.6 | Enhanced `enum` |
| 5 | Sealed type hierarchy | Domain Model | S2 | D-2, FR-020–FR-025 | `sealed interface` |
| 6 | Builder | Creational | S1, S3 | D-1, D-11, FR-030 | — |
| 7 | Factory Method | Creational | S3 | D-11, FR-050 | — |
| 8 | Static Factory | Creational | S1, S2 | D-2, FR-036 | `record` static factory |
| 9 | Strategy — Volume Resolution | Structural | S3, S6b | D-11, FR-050–FR-051 | `sealed interface` |
| 10 | Strategy — Price Evaluation | Structural | S2, S5a | D-2, FR-020, FR-040 | Pattern matching `switch` |
| 11 | Strategy — Materialization | Structural | S3, S6b | FR-056, V3.0 §4 | — |
| 12 | Composite | Structural | S2 | D-2, FR-025 | Pattern matching |
| 13 | Decorator / Filter Chain | Structural | Cross-cutting | O-2, V2.0 §11 | — |
| 14 | Observer / Domain Events | Behavioral | S3, S5a, S5b, S8 | V3.0 §8, FR-052a–c | `record` |
| 15 | Template Method | Behavioral | S5a, S5b, S6, S6b | FR-056 | — |
| 16 | State Machine | Behavioral | S3 | FR-054, V3.0 §3.2.6 | Enum methods |
| 17 | Command | Behavioral | S1, S3 | FR-001–FR-005, FR-037 | `record` |
| 18 | Repository | Persistence | S1, S3, S5a | V2.0 §5 | — |
| 19 | Specification (query) | Persistence | S3 | FR-051, V2.0 §7 | — |
| 20 | Unit of Work (batch flush) | Persistence | S3 | V2.0 §17.1 | — |
| 21 | Identity Map | Persistence | S3 | V2.0 §12 | — |
| 22 | Dual DataSource | Persistence | S1, S3, S5a, S6 | V2.0 §3.4, P4 | — |
| 23 | Sequence-based ID | Persistence | S1, S3 | V2.0 §5.1, P3 | — |
| 24 | Transactional Outbox | Integration | S3, S8 | V2.0 §13.4, D-9 | — |
| 25 | Saga (choreography) | Integration | S1, S3 | V2.0 §13.5, FR-001 | — |
| 26 | Idempotent Consumer | Integration | S3 | V2.0 §13.3, D-7 | — |
| 27 | Event-Carried State Transfer | Integration | S3, S5a, S5b | V3.0 §8, FR-052a | `record` |
| 28 | CQRS (implicit) | Integration | S1, S6, S6b | D-1, P4 | — |
| 29 | Read-Through | Caching | S3, S6 | V2.0 §12.6, FR-079 | — |
| 30 | Cache-Aside + Event Invalidation | Caching | S3, S6 | V2.0 §12.5 | — |
| 31 | Pipeline Batching | Caching | S6, S6b | FR-079, V2.0 §14 | — |
| 32 | `@TenantAware` Aspect | Cross-Cutting | All (S1–S8) | V2.0 §11, P1 | — |
| 33 | Functional Interfaces | Cross-Cutting | S3, S5a | D-11, FR-051 | Functional interfaces |
| 34 | Immutability Enforcement | Cross-Cutting | S1, S3 | P2, FR-050 | `record` |
| 35 | Bitemporal Audit | Cross-Cutting | S1, S3, S5a | D-1, FR-006–FR-009, V2.0 §10 | — |

---

## 5. Consequences

### Positive

- **Type-safe domain model.** Sealed interfaces + pattern matching `switch` enforce exhaustive handling of `PriceExpression` variants at compile time. Adding a new expression type produces compiler errors at every unhandled `switch`, preventing silent omission.
- **Immutability by default.** Java `record` types for value objects and event payloads eliminate accidental mutation. Combined with DB-level immutability triggers (P2), the system enforces the append-only discipline required by bitemporality (FR-006).
- **Testable strategies.** Each strategy (volume resolution, price evaluation, materialization) is independently testable with stub inputs. The Composite pattern for `PriceExpression` enables property-based testing of formula evaluation against reference deal examples (§2.7).
- **Clear layering.** The Entity → Measure → Cache dependency chain (S1/S2 → S5 → S6/S6b/S7) is preserved in the pattern structure: repositories own entities, template methods derive measures, caches are rebuildable projections.
- **Regulatory compliance.** Bitemporal audit patterns (D-1, FR-006–FR-009) combined with the idempotent consumer and transactional outbox guarantee reproducibility: any settlement value can be reconstructed from its input-version-set.
- **Unified volume resolution.** One code path for all trades (D-11) — no category branching. The same `VolumeReference × multiplier` pattern works for power PPAs, DA fills, and future commodity extensions (gas, oil, ags per FR-013a).

### Negative

- **Learning curve.** Sealed interfaces with pattern matching are less familiar to developers experienced only with inheritance-based dispatch. Training investment needed.
- **Class count.** ~35 patterns implies more classes than an anemic domain model. Each value object, strategy, and event payload is a separate type. Mitigated by Java records (minimal boilerplate) and clear package structure.
- **Hibernate compatibility.** Records cannot be JPA entities (no default constructor, no mutable fields). Value objects used as embeddables require careful mapping or wrapper approaches. Entities remain traditional classes; records are for value objects, events, and commands.

### Neutral

- **Pattern scope.** 35 patterns is a high count, but each is narrowly scoped to a specific spec requirement. No pattern is speculative — every one traces to at least one FR-nnn or D-nn.
- **Virtual threads.** Listed as a consideration, not a commitment. The chunk materialization use case (I/O-bound, independent tasks) is a natural fit, but adoption depends on Spring Boot 3.3 virtual thread support maturity and connection pool compatibility.

---

## 6. Compliance Matrix

### 6.1 Design Decisions → Patterns

| Decision | Description | Implementing Patterns |
|---|---|---|
| D-1 | Ledger grain = trade-leg × delivery-month block; signed qty; no interval fan-out in S1 | #1 Entity vs Measure, #2 Aggregate Root, #6 Builder, #34 Immutability, #35 Bitemporal Audit |
| D-2 | Price = expression ref; fixed price = degenerate expression | #3 Value Object, #5 Sealed Hierarchy, #8 Static Factory, #10 Strategy (Price), #12 Composite |
| D-3 | Forward marks ephemeral; settlement bitemporal; EOD strike = month-bucket with stamps | #1 Entity vs Measure, #15 Template Method, #28 CQRS |
| D-4 | Optimized version-binding; `active_leaves` captured at first resolution | #12 Composite, #15 Template Method |
| D-5 | Peak is interval-dimension data, calendar-versioned; never on position rows | #1 Entity vs Measure, #33 Functional Interfaces |
| D-6 | Dual units (MW+MWh) materialized only in cache/rollups; single canonical in ledger | #1 Entity vs Measure, #29 Read-Through, #31 Pipeline Batching |
| D-7 | Batch authoritative, events additive; re-derive-not-delta idempotency | #26 Idempotent Consumer, #15 Template Method, #14 Observer |
| D-8 | Entity/measure distinction; netting is projection policy | #1 Entity vs Measure, #28 CQRS |
| D-9 | Shared monthly partitions with tenant as leading key; S5c on strike-month axis | #24 Transactional Outbox, #32 @TenantAware Aspect, #23 Sequence-based ID |
| D-10 | All interval structure via MarketCalendar; no timestamp arithmetic | #3 Value Object (DeliveryPeriod), #33 Functional Interfaces |
| D-11 | Unified volume resolution: VolumeReference → VolumeSeries × multiplier | #9 Strategy (Volume), #7 Factory Method, #33 Functional Interfaces, #27 Event-Carried State Transfer |
| D-12 | S6b trade_interval_cache: optional, rebuildable, event-driven | #11 Strategy (Materialization), #30 Cache-Aside, #15 Template Method |

### 6.2 Subsystem Coverage

| Subsystem | Description | Patterns |
|---|---|---|
| S1 | Position Ledger | #1, #2, #6, #17, #18, #22, #23, #25, #34, #35 |
| S2 | PriceExpression | #3, #5, #8, #10, #12 |
| S3 | VolumeSeries (V3.0 unified) | #2, #4, #7, #9, #14, #16, #18, #19, #20, #21, #23, #24, #27, #30, #34, #35 |
| S4 | Market Data Store | #10, #27 |
| S5a | Settlement cells | #1, #4, #14, #15, #18, #22, #35 |
| S5b | Forward marks | #1, #14, #15, #27 |
| S5c | EOD struck marks | #1, #15, #28 |
| S6 | Slot Cache | #1, #22, #28, #29, #30, #31 |
| S6b | Trade Interval Cache | #9, #11, #15, #28, #30, #31 |
| S7 | Rollups | #15, #33 |
| S8 | Dependency index | #14, #24 |

### 6.3 Key FR Numbers → Patterns

| FR Range | Topic | Patterns |
|---|---|---|
| FR-001–FR-003 | Entity vs Measure fundamentals | #1 |
| FR-004–FR-005 | Grain follows lifecycle of change | #1, #2 |
| FR-006–FR-009 | Bitemporality | #35 |
| FR-020–FR-025 | Market, DeliveryPoint, MarketCalendar | #3, #5, #10, #12 |
| FR-030–FR-039 | Position Ledger attributes & lifecycle | #6, #17, #34 |
| FR-040 | PriceExpression reference | #5, #10, #12 |
| FR-050–FR-056 | Volume series interface & resolution | #2, #7, #9, #11, #15, #19 |
| FR-054 | QualityState transitions | #4, #16 |
| FR-075 | Forward marks ephemeral | #1, #28 |
| FR-079 | EOD struck marks & cache reads | #29, #31 |
| FR-106 | Idempotent Kafka processing | #26 |
| FR-120/FR-122 | Multitenancy | #32 |

---

## 7. References

### Specification Documents

| Document | Path | Version |
|---|---|---|
| Functional Specification | `docs/functional-spec/functional-spec-position-valuation-v1.0.md` | 1.0 |
| Volume Series Domain Model | `docs/functional-spec/VOLUME_SERIES_SPEC-V3_0.md` | 3.0 |
| Data Architecture | `docs/technical-spec/VOLUME_SERIES_DATA_ARCHITECTURE-V2_0.md` | 2.0 |
| Design Context & Rationale | `docs/context/CONTEXT-position-valuation-design.md` | — |
| Plain Language Guide | `docs/spec-in-layman-language/README.md` | — |

### Java 21 JEP References

| JEP | Feature | Patterns Using It |
|---|---|---|
| JEP 395 | Records | #3, #8, #14, #17, #27, #34 |
| JEP 409 | Sealed Classes | #5, #9, #10 |
| JEP 441 | Pattern Matching for `switch` | #5, #10, #12 |
| JEP 378 | Text Blocks | JPQL queries, SQL fragments |
| JEP 444 | Virtual Threads | #11 (consideration) |
| JEP 431 | Sequenced Collections | #2 |

### Platform Versions

| Component | Version | Constraint Source |
|---|---|---|
| Java | 21 (LTS) | V2.0 §1.4, Context §11 |
| Spring Boot | 3.3 | V2.0 §1.4 |
| Aurora PostgreSQL | 16 | V2.0 §1.4, P0 |
| Kafka | 3.7 (KRaft) | V2.0 §1.4 |
| Redis | 7 | V2.0 §1.4 |
| pg_partman + pg_cron | — | V2.0 §6, P6 |
| Flyway | — | V2.0 §16.1 |
| HikariCP + PgBouncer | — | V2.0 §3.4, P4 |
