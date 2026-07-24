# Claude Code Prompt — Position & Valuation Technical Specification (Library + Guice)

Copy everything below the horizontal rule into Claude Code as the initial user message.

---

## Task

Produce the **Technical Specification** for the Position & Valuation system, targeting the **Library + Guice** variant defined in ADR-001. This is the successor document to the functional spec v1.0 (§1.4 mandates it).

**Deliverable:** `docs/technical-spec/TECH-SPEC-position-valuation-library_guice-v1.0.md`

Do **not** run code, execute tests, or scaffold modules. This task produces one markdown document.

## Read first (in this order)

1. `docs/technical-spec/ADR-001-IMPLEMENTATION-PATTERN-CATALOG-library_guice.md` — pattern catalog (35 patterns), module structure, Java 21 feature map. This drives **what** to produce.
2. `docs/functional-spec/functional-spec-position-valuation-v1.0.md` — 120 FR-nnn rules and 12 design decisions D-1..D-12. This drives **why**.
3. `docs/spec-in-layman-language/README.md` — plain-language guide. Use only when the functional spec's terseness needs a domain assist.
4. **If present:** `docs/functional-spec/VOLUME_SERIES_SPEC-V3_0.md` and `docs/technical-spec/VOLUME_SERIES_DATA_ARCHITECTURE-V2_0.md`. If either is absent, **do not fabricate content**; list the missing doc at the top of the tech spec under `## Assumptions & Gaps` and continue with what the functional spec + ADR say.

## Ground rules

### Architectural constraints (non-negotiable — the ADR is authoritative)

- `pv-domain` has **zero** framework dependencies. JDK only. `jakarta.inject` (JSR-330) is the sole exception because it is portable. No `com.google.inject.*`, no Spring, no Hibernate types.
- Ports live in `pv-domain/port/`. JPA classes live in `pv-persistence/`. Kafka in `pv-kafka/`. Redis in `pv-redis/`. Guice bindings only in `pv-guice/`.
- Adapters **never** depend on each other. If Redis needs data, it goes through the domain port, not the JPA adapter.
- Port interfaces are pure Java. No annotations except optionally `@FunctionalInterface`.
- If you find yourself adding a `@Transactional` in `pv-domain`, stop — that's a wiring concern.

### Traceability rule

Every interface, record, sealed hierarchy, enum, JPA entity, Guice module, and Kafka topic in the spec **must cite**:
- At least one FR-nnn from the functional spec
- At least one Pattern # (1–35) from ADR-001
- The subsystem code (S1..S8, S6b) it belongs to

Cite inline in a header line:

> ### `VolumeSeriesRepository` — Pattern #18, FR-050, FR-051, S3, V3.0 §7.1

### Code excerpts, not full implementations

- Full record declarations: **yes**
- Full sealed hierarchy `permits` clauses: **yes**
- Full port interface signatures with method Javadoc citing FR: **yes**
- Full Guice module binding blocks: **yes**
- JPA entity skeletons (class + `@Id`/`@SequenceGenerator`/key `@Column`s + PK/index notes): **yes**
- Adapter algorithms: **10–30 line excerpts** showing the non-obvious logic only. No CRUD boilerplate.
- SQL/JPQL: text blocks, key queries only. No full DDL — the data architecture spec (V2.0) owns DDL. Refer to it.
- Full class implementations: **no**.

### Java 21 features to apply consistently

- `record` for every value object, event payload, and command payload
- `sealed interface … permits …` for `PriceExpression`, `VolumeResolver`, `MaterializationStrategy`
- Pattern matching `switch` for `PriceExpression` tree walkers and event dispatch — show at least one exhaustive `switch` with all arms
- `SequencedCollection` / `SequencedSet` for ordered `VolumeInterval` collections
- Virtual threads via `Executors.newVirtualThreadPerTaskExecutor()` for chunked materialization (S3, S6b)
- Text blocks for JPQL and multi-line SQL fragments

## Deliverable structure

Single markdown file, sections in this exact order. If a section is not applicable to the library-Guice variant, keep the header and write `Not applicable in this variant — see Spring Boot variant §x.y for the equivalent.`

1. **Metadata & status** — mirror the ADR-001 table (Status, Date, Deciders, Companion Specs, Variant).
2. **Scope & non-scope** — what this doc specifies; what defers to `VOLUME_SERIES_DATA_ARCHITECTURE-V2_0.md` (DDL, partitioning SQL, RLS), to the UX spec, and to trade capture.
3. **Assumptions & Gaps** — list any of the four input docs that were missing; call out anything you had to infer.
4. **Module structure** — reproduce ADR §1.1 tree; add a per-module `build.gradle` **dependency skeleton only** (deps + repositories, no plugins config).
5. **Domain model — value objects, events, commands** (`pv-domain`)
   - Records: `DeliveryPeriod`, `SeriesKey`, `Money`, `TimeRange`, `DeliveryRange`, `VolumeReference` — full declarations, static factories, validation invariants.
   - Event records: `VolumePublished`, `VolumeSuperseded`, `VolumeChunkMaterialized`, `SettlementComputed`.
   - Command records: `TradeCapture`, `TradeAmend`, `TradeCancel`.
   - Enums with behavior: `QualityState` (transition guards as methods), `SeriesType`, `VolumeUnit`.
6. **Domain model — aggregates & entities** (`pv-domain` interfaces + `pv-persistence` JPA classes clearly separated)
   - `VolumeSeries` + `VolumeInterval` — aggregate root, `SequencedSet` intervals, invariants (FR-050, D-11).
   - `MeteredActualVolumeSeries` + `MeteredActualInterval` — forward-link append-only versioning.
   - `PositionLedgerEntry` — bitemporal columns, Builder (Pattern #6), immutability (Pattern #34).
7. **PriceExpression — sealed hierarchy** (S2)
   - Full `sealed interface PriceExpression permits …` with all 13 permitted types (3 leaf + 10 operator per FR-048h).
   - Each leaf and operator as a record.
   - `PriceEvaluator` port + one representative pattern-match `switch` excerpt covering: `ConstantLeaf`, `MarketDataLeaf`, `IndexLeaf`, `Clamp`, `Escalate`, `ConditionalGate`. Include `active_leaves` capture per FR-048f.
   - Purpose-based leaf resolution (FR-048e): forward vs settlement dispatch.
8. **S1 — Position Ledger**
   - Bitemporal JPA entity skeleton, key columns, PK strategy (`SEQUENCE` allocationSize=50, Pattern #23), index list.
   - `PositionLedgerRepository` port + `JpaPositionLedgerRepository` adapter method excerpt for bitemporal as-of query (FR-007 — spell out the `valid_from ≤ B < valid_to AND known_from ≤ K < known_to` predicate as a text-block JPQL).
   - Command handlers (`TradeCapture`/`Amend`/`Cancel`) — interface + one representative handler excerpt.
   - Outbox write within same transaction (Pattern #24).
9. **S3 — Volume Series**
   - `VolumeResolver` sealed interface + `ProfileResolver`, `ForecastResolver` (D-11: no branching in code — resolution is data-driven).
   - `VolumeSeriesFactory.createForTrade()` — Pattern #7 routing PROFILE vs FORECAST.
   - `VolumeSeriesRepository` + `VolumeSeriesSpec` (Pattern #18, #19) — functional-interface specification composable via `.and()`/`.or()`.
   - `MaterializationStrategy` sealed interface + `RollingHorizonStrategy` excerpt.
   - `BatchWriter` (Pattern #20) — flush/clear every 50 entities.
   - Event publishing: `DomainEventPublisher` port; `VolumePublished` payload including `series_key + version_id + delivery_range + quality_state` (FR-052a, Pattern #27).
10. **S4 — Market Data Store**
    - `MarketDataPort` read-only interface, version pinning for reproducibility (FR-048f `input_version_set`).
11. **S5 — Valuation** (S5a settlement / S5b forward marks / S5c EOD struck)
    - Settlement cells: entity skeleton, `input_version_set` capture columns.
    - Forward marks: ephemeral, in-memory or short-TTL cache — no persistence (FR-075).
    - EOD struck marks: bitemporal freeze at month-bucket grain (FR-079, D-3).
    - `AbstractMaterializationJob` (Pattern #15) template with hooks `resolveVolume()` / `evaluatePrice()` / `writeResult()`.
12. **S6 — Slot Cache & S6b — Trade Interval Cache**
    - `VolumeCache` port (Pattern #29 Read-Through, #30 Cache-Aside + Event Invalidation, #31 Pipeline Batching).
    - `RedisVolumeCache` — key scheme `vol:{tenant_id}:{series_key}:{interval_start_iso}`, `MGET` batching (FR-079).
    - Invalidation event handlers wired to `VolumeSuperseded`.
    - S6b materialization strategy dispatch (Pattern #11) — eager vs rolling vs chunk.
13. **S7 — Rollups & S8 — Dependency Index**
    - Rollup query interfaces, functional-interface aggregators.
    - Dependency index topology: what invalidates what on `VolumeSuperseded` / `SettlementComputed`.
14. **Cross-cutting concerns**
    - `TenantContext` port + `@TenantAware` annotation — both in `pv-domain`.
    - `TenantInterceptor` (full class, `pv-guice`) — Pattern #32.
    - `DataSourceRouter` port + `DualHikariDataSourceRouter` — `@Named("writer")` / `@Named("reader")` bindings (Pattern #22).
    - `BitemporalAuditListener` (`pv-persistence`) — Pattern #35. Note DB-trigger defense-in-depth per V2.0 §10.3.
15. **Kafka topics & event contracts**
    - Topic names, key strategy, headers, JSON schema derived from event records.
    - Outbox schema (columns, poller cadence, ordering guarantee).
    - Idempotency keys per topic — natural key `(trade_id, trade_version)` for trade events (FR-106, D-7).
16. **Guice wiring**
    - `DomainModule`, `PersistenceModule`, `TenantModule`, `EventModule`, `CacheModule`, `KafkaModule` — full binding blocks.
    - Bootstrap `main` — module composition order.
    - Scoping rules — `Singleton` for adapters/factories; `Provider<EntityManager>` for per-request EM.
17. **Transactions**
    - Choose **one** of: `guice-persist` `@Transactional` OR explicit `UnitOfWork` wrapper. Justify in 3–5 lines. Show one working example either way.
    - Outbox-write-in-same-tx pattern — explicit sequencing (commit → relay, not relay → commit).
18. **Testing strategy**
    - Unit tests: plain JUnit + hand-mocked port implementations. No Guice injector, no Spring context.
    - Integration tests: Testcontainers Postgres 16 for `pv-persistence`.
    - Contract tests: verify port ↔ adapter conformance.
    - Bitemporal reconstruction test template — one worked example.
19. **Startup & bootstrap** — `PositionValuationApp.main`, injector creation, EntityManagerFactory init order, Kafka consumer start, outbox poller start.
20. **Compliance matrix** — three tables:
    - D-1..D-12 → patterns → interfaces/classes in this spec
    - FR ranges → interfaces/classes
    - S1..S8, S6b → interfaces/classes
21. **Open items for implementation** — mirror and extend functional-spec §16.
22. **References** — reproduce ADR §7.

## Style

- Match the functional spec's tone: numbered rules, definitive. Introduce **TR-nnn** (Technical Rules) that **extend** an FR (do not restate it). Example: `TR-018 — PositionLedgerEntry bitemporal columns are stored as UTC-normalised timestamptz. (Extends FR-006.)`
- Tables for property lists; mirror ADR §2 column layout where possible.
- Text blocks for every multi-line JPQL/SQL fragment.
- Cite FR numbers instead of restating the functional rule.
- No cheerleading, no "as we saw above", no meta-commentary about the writing process.
- Where a design decision is arbitrary between equivalents (Jedis vs Lettuce; `guice-persist` vs `UnitOfWork`), state both, pick one, give a one-sentence rationale.

## What NOT to do

- No Spring / Spring Boot / Spring Data JPA code. The only reference to Spring Boot is the one-line callout at the top of §16 pointing to the Spring variant ADR.
- No DDL — the data architecture spec owns it. Refer to it by section.
- No verbatim restatement of the functional spec. Cite it.
- No hand-waving on bitemporal reconstruction — the as-of query pattern must appear in a text block (FR-007).
- No `Optional<T>` fields inside records or JPA entities. Use `Optional` only as return types on ports.
- No CRUD boilerplate in code excerpts. Show only what is non-obvious or spec-driven.
- No lombok. Records + explicit builders where builders are needed (Pattern #6).
- No classpath scanning, no auto-configuration, no annotation-driven magic beyond `@Inject` / `@Named` / `@TenantAware`.
- Do not introduce new pattern numbers beyond the 35 in ADR-001. If you need a new one, flag it in Open Items instead.

## Working rhythm

1. Read the four docs in the order listed above.
2. Produce a **section-by-section outline** (headers + one bullet each on what will go into it) as your first response. Do not write the spec yet. Wait for approval.
3. On approval, write the tech spec. If it grows past ~2500 lines, split by group:
   - `TECH-SPEC-...-part1-domain-and-ports.md` covers §1–§7
   - `TECH-SPEC-...-part2-subsystems.md` covers §8–§13
   - `TECH-SPEC-...-part3-crosscutting-and-wiring.md` covers §14–§22
   Cross-link the parts at the top of each file.
4. Produce the compliance matrix **last**. Walk every FR range, every D-n decision, every S-code, every pattern # — flag any that end up with zero coverage in the spec.
5. Do not open any pull request or commit anything. Leave the files staged in the working tree.

## Success criteria

- A senior Java architect can hand `pv-domain` sections to a developer and get compilable stub code from them without asking clarifying questions.
- Every FR from the functional spec and every pattern from ADR-001 is reachable via the compliance matrix.
- The document does not mention Spring except in the single alternative-variant callout.
- Bitemporal reconstruction (FR-006–FR-009) is spelled out with a working JPQL text block, not paraphrased.
- Guice module bindings compile in principle — types and method signatures line up with the port interfaces in earlier sections.
