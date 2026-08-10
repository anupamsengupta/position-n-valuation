# Functional Specification -- Position & PnL Dashboard

**Feature slug:** position-pnl-dashboard
**Version:** 1.0 (Draft)
**Status:** DRAFT -- pending review
**Date:** 2026-08-10
**Spec basis:** FR-090, FR-110, FR-113, FR-035; S1 Position Ledger, S5a Settlement Cells, S7 Rollup Aggregates

---

## 1. Context

### 1.1 Problem Statement in Domain Language

Portfolio managers, traders, and risk analysts need a consolidated view of their EU power positions and mark-to-market PnL, structured as a hierarchical drill-down from portfolio-level summaries down to 15-minute settlement intervals. Today the platform materializes settlement cells (S5a) at quarter-hour granularity, rolls them up into coarse-grain rollup cells (S7) per (delivery_point, portfolio) at WEEKLY/MONTHLY/YEARLY periods, and maintains a bitemporal position ledger (S1) at trade-leg-times-delivery-month grain. No unified read path currently composes these three subsystems into the four-level dashboard view that front-office and middle-office users require for intraday risk monitoring, EOD PnL review, and portfolio-level oversight.

The dashboard must serve four hierarchical levels:

1. **Portfolio cards** -- aggregate net MW, net MWh, settled value, market value, and PnL across all positions in a portfolio for a user-selected date range. One card per portfolio the user has visibility into.
2. **Rollup cell grid** -- the S7 rollup cells for a selected portfolio (or cross-portfolio), filterable by WEEKLY, MONTHLY, or YEARLY granularity, showing peak/off-peak split. This is the FR-090 materialized view.
3. **Position ledger trade-level view** -- for a selected portfolio-times-month (or portfolio-times-week), the underlying S1 position ledger entries (current knowledge, ACTIVE status) with their per-month summary aggregates (PositionMonthSummary).
4. **15-minute interval detail** -- for a selected position-times-month, the S5a settlement cells at native quarter-hour resolution, showing price, volume (MW/MWh), trade amount, market price, market amount, and PnL per interval.

Each level is reached by drilling into the level above. The data is read-only; no mutations originate from this feature.

### 1.2 Relationship to Existing Spec

This feature implements the read paths described in FR-110 (position grid), FR-113 (drill-down), and FR-114 (valuation views) from the binding functional spec. It consumes only materialized/derived data (rollup cells, settlement cells, position ledger queries) and does not introduce new write paths or new domain events. The dashboard is a pure query-side feature.

---

## 2. Actors

| Actor | Role in This Feature |
|---|---|
| **Portfolio Manager** | Primary user. Views portfolio-level PnL cards, drills into rollup grids and individual trade positions. Needs cross-portfolio comparison. Typically monitors 3-15 portfolios. |
| **Trader** | Views positions within their assigned portfolios. Drills to 15-min interval detail to understand shaped/profiled volume and price outcomes. May filter by delivery point. |
| **Risk Analyst (Middle Office)** | Reviews PnL across all portfolios for a tenant. Uses the dashboard for EOD PnL sign-off, variance analysis, and limit monitoring. Requires peak/off-peak split visibility. |
| **System (implicit)** | S7 rollup materialization (triggered by SettlementComputed events) must have completed before the dashboard can display current data. The dashboard does not trigger materialization; it reads the latest materialized state. |

**Authorization note:** Portfolio-level visibility filtering (which portfolios an actor can see) is out of scope for this functional spec. The production hosting layer will enforce portfolio-level entitlements via the tenant and user context. The dashboard read paths must accept a portfolio filter set and must not assume the caller can see all portfolios within a tenant.

---

## 3. Business Events

This feature is read-only. It does not produce domain events. It consumes the outputs of the following upstream events, which must have been processed before the dashboard reflects current state:

| Upstream Event | Source | Dashboard Impact |
|---|---|---|
| `SettlementComputed` | S5a settlement materialization | Settlement cells become available for Level 4 (interval detail). Triggers S7 rollup materialization. |
| `RollupMaterialized` (or equivalent S7 completion) | S7 rollup materialization service | Rollup cells become available for Levels 1 and 2. |
| `PositionEntryCaptured` | Trade capture flow | New position ledger entries become visible in Level 3. |
| `MarketDataUpdated` | Market data revaluation | Triggers settlement revaluation, which cascades to rollup refresh. Dashboard reflects updated PnL once rollups are re-materialized. |
| `VolumeSuperseded` | Volume series update | Same cascade as market data: settlement revaluation, then rollup refresh. |

**Staleness indicator:** Because rollup materialization is event-driven and not instantaneous, the dashboard should expose the `versionHash` and `computedAt` timestamp from the underlying rollup cells so the UI can display a "last updated" indicator. If rollup cells for a requested period do not yet exist (no settlement has occurred for far-future months), the dashboard must return empty data for those periods, not an error.

---

## 4. Regulatory Mapping

| Regulation | Applicability |
|---|---|
| **REMIT (Reg. 1227/2011)** | Not directly applicable. REMIT transaction reporting and fundamental data reporting are obligations on the capture/execution side, not the read/display side. However, the position ledger (S1) that this dashboard reads is the same ledger used for REMIT audit trail reconstruction (FR-007, FR-009). The dashboard must therefore never display stale or inconsistent ledger state that could mislead a compliance officer performing REMIT trade reconstruction. The bitemporal as-of query capability (Level 3) supports this. |
| **EMIR (OTC derivative reporting)** | Not directly applicable to the dashboard itself. EMIR obligations apply to trade reporting, not position display. |
| **MiFID II (RTS 22)** | Not directly applicable. Transaction reporting is a back-office function. The dashboard may be used informally by compliance to spot anomalies, but it is not a MiFID II reporting tool. |
| **Internal risk policy** | The dashboard is a tool for real-time and EOD PnL monitoring. While no specific regulation mandates a PnL dashboard, EU power trading firms operating under ACER/NRA oversight and internal risk frameworks typically require daily position and PnL reconciliation. This dashboard supports that operational requirement. |

**Conclusion:** No regulatory obligations directly attach to this feature. The dashboard is an internal operational and risk management tool. However, the data it displays (position ledger, settlement cells) is regulatory-grade and must remain consistent with the bitemporal source of truth.

---

## 5. Acceptance Criteria

### 5.1 Level 1 -- Portfolio Cards

**AC-1.1: Portfolio card displays aggregated PnL**

```
Given a tenant TN_0042 with portfolios [WIND_PPA, SOLAR_PPA, SPOT_TRADING]
  And each portfolio has materialized S7 rollup cells for the date range 2026-01-01 to 2026-06-30
  And the user requests portfolio cards for that date range
When the dashboard loads Level 1
Then one card is rendered per portfolio visible to the user
  And each card shows:
    - portfolioId
    - total netMwh (sum of rollup cell netMwh across all delivery points and periods in range)
    - weighted-average netMw (time-weighted average per FR-035)
    - total settledValue (sum)
    - total marketValue (sum)
    - total pnl (sum)
    - currency (all rollup cells within a portfolio-currency group; see Edge Case 6.6)
```

**AC-1.2: Empty portfolio shows zero card**

```
Given portfolio EMPTY_BOOK has no position ledger entries and no rollup cells
When the user requests portfolio cards
Then a card for EMPTY_BOOK is either omitted or displayed with all-zero measures
  (product decision -- see Open Question 8.1)
```

**AC-1.3: Portfolio card respects date range filter**

```
Given portfolio WIND_PPA has rollup cells for 2025-01 through 2027-12
  And the user filters to 2026-Q1 (2026-01-01 to 2026-03-31)
When portfolio cards are computed
Then only rollup cells with periodStart >= 2026-01-01 AND periodEnd <= 2026-03-31 are included
  And rollup cells for 2025-12 and 2026-04 are excluded
```

**AC-1.4: Peak/off-peak aggregation on portfolio cards**

```
Given rollup cells are split by isPeak = true/false (FR-090)
When portfolio cards aggregate
Then the card shows combined (peak + off-peak) totals by default
  And an optional peak/off-peak breakdown is available as a sub-row or toggle
```

### 5.2 Level 2 -- Rollup Cell Grid

**AC-2.1: Monthly rollup grid for a portfolio**

```
Given portfolio WIND_PPA for tenant TN_0042
  And S7 rollup cells exist at MONTHLY granularity for delivery points [DE_LU, AT]
  And the user selects MONTHLY granularity and date range 2026-01 to 2026-12
When the rollup grid is requested
Then the grid returns rollup cells grouped by (deliveryPointId, portfolioId, periodStart)
  And each cell shows: netMw, netMwh, price, marketPrice, settledValue, marketValue, pnl, forwardMarkValue, currency, isPeak
  And cells are ordered by periodStart ascending
```

**AC-2.2: Weekly rollup grid**

```
Given the user selects WEEKLY granularity for the same portfolio and range
When the rollup grid is requested
Then rollup cells at WEEKLY granularity are returned
  And ISO week boundaries are used (Monday 00:00 CET/CEST to following Monday 00:00 CET/CEST)
  And partial weeks at range boundaries are included (not clipped)
```

**AC-2.3: Cross-portfolio rollup view**

```
Given the user selects "All Portfolios" and MONTHLY granularity
When the rollup grid is requested
Then rollup cells for all portfolios visible to the user are returned
  And cells are grouped by portfolioId, then by (deliveryPointId, periodStart)
  And a cross-portfolio total row is computable by the presentation layer
    (netMwh = sum, netMw = TWA re-aggregation, amounts = sum)
```

**AC-2.4: Rollup grid respects RollupQueryService contract**

```
Given the existing RollupQueryService.findByRange(tenantId, deliveryPointId, portfolioId, rangeStart, rangeEnd, granularity)
When the dashboard queries Level 2
Then it delegates to this service interface
  And when portfolioId or deliveryPointId is null, the query returns cells across all portfolios or delivery points respectively
```

**AC-2.5: DAILY granularity gap**

```
Given the user requests DAILY granularity
  And the S7 rollup currently materializes only WEEKLY, MONTHLY, YEARLY
When the rollup grid is requested at DAILY
Then the system either:
  (a) returns an error indicating DAILY rollups are not materialized, OR
  (b) dynamically aggregates from S5a settlement cells for the requested range
  (see Open Question 8.2)
```

### 5.3 Level 3 -- Position Ledger Trade-Level View

**AC-3.1: Trade-level positions for a portfolio-month**

```
Given portfolio WIND_PPA, delivery month 2026-03
  And the position ledger (S1) contains entries for trades [T-7788, T-8899] in that portfolio and month
  And both entries have status = ACTIVE and knownTo = null (current knowledge)
When the user drills from the rollup grid cell (WIND_PPA, 2026-03) to Level 3
Then the dashboard shows all current-knowledge ACTIVE position ledger entries
  for portfolioId = WIND_PPA and deliveryRange containing 2026-03
  And each entry shows: tradeId, tradeLegId, tradeVersion, deliveryRange,
    quantity (signed), volumeUnit, portfolioId, deliveryPointId, status
  And PositionMonthSummary aggregates are shown per entry:
    totalMwh, avgMw, totalAmount, totalMarketAmount, totalPnl, avgPrice, avgMarketPrice, currency, cellCount
```

**AC-3.2: Superseded and cancelled entries excluded by default**

```
Given position entry for T-7788 has been amended (original version: status=SUPERSEDED, knownTo set)
  And the amended version has status=ACTIVE, knownTo=null
When Level 3 displays positions
Then only the ACTIVE/current-knowledge version is shown
  And an "audit trail" toggle or link is available to view the full bitemporal history
```

**AC-3.3: Position ledger entry links to drill-down**

```
Given the user views position entry for T-7788, delivery month 2026-03
When the user clicks to drill into that position
Then the dashboard navigates to Level 4 (15-min interval detail) for that position and month
```

### 5.4 Level 4 -- 15-Minute Interval Detail

**AC-4.1: Settlement cells for a position-month**

```
Given position entry P-001 (T-7788, WIND_PPA, 2026-03)
  And S5a settlement cells exist for all 2976 quarter-hours of March 2026
    (31 days * 96 intervals; non-DST month)
When the user drills to Level 4 for P-001 / 2026-03
Then all settlement cells for positionId = P-001 within 2026-03 are returned
  ordered by intervalStart ascending
  And each cell shows: intervalStart, intervalEnd, price, volumeMw, volumeMwh,
    amount, marketPrice, marketAmount, pnl, currency, cellStatus, valuationType
```

**AC-4.2: DST transition month -- March (spring forward)**

```
Given position entry P-002 covers delivery month 2026-03
  And DST spring-forward occurs on 2026-03-29 in Europe/Berlin (CET to CEST)
  And the transition day has 92 quarter-hour intervals (23 hours * 4)
When Level 4 displays intervals for 2026-03
Then March 2026 shows (30 * 96) + 92 = 2972 intervals (not 2976)
  And the missing hour (02:00-03:00 CET) has no intervals
  And interval timestamps are in UTC (Instant), with the UI responsible for wall-clock rendering
```

**AC-4.3: DST transition month -- October (fall back)**

```
Given position entry P-003 covers delivery month 2026-10
  And DST fall-back occurs on 2026-10-25 in Europe/Berlin (CEST to CET)
  And the transition day has 100 quarter-hour intervals (25 hours * 4)
When Level 4 displays intervals for 2026-10
Then October 2026 shows (30 * 96) + 100 = 2980 intervals (not 2976)
  And the repeated hour (02:00-03:00 CET appears twice) has 8 intervals
    (4 in CEST, 4 in CET), each with distinct intervalStart/intervalEnd in UTC
```

**AC-4.4: Partial-month position**

```
Given position P-004 has deliveryStart = 2026-03-15T00:00Z and deliveryEnd = 2026-03-31T22:00Z
When Level 4 is requested for P-004 / 2026-03
Then only settlement cells within [2026-03-15T00:00Z, 2026-03-31T22:00Z) are returned
  And intervals outside the position's delivery window are omitted
```

**AC-4.5: Missing settlement cells**

```
Given position P-005 covers 2027-06 (far-future, no settlement yet)
  And no S5a settlement cells have been materialized for this position-month
When Level 4 is requested
Then the dashboard returns an empty interval list
  And a message indicates that settlement has not yet been computed for this period
```

**AC-4.6: Interval detail aggregation footer**

```
Given all settlement cells for P-001 / 2026-03 are returned
When the UI renders the interval grid
Then a footer row shows:
  - netMw = time-weighted average of volumeMw (FR-035: TWA on roll-up)
  - netMwh = sum of volumeMwh (FR-035: MWh sums on roll-up)
  - totalAmount = sum of amount
  - totalMarketAmount = sum of marketAmount
  - totalPnl = sum of pnl
  And these must match the corresponding PositionMonthSummary values from Level 3
    (within rounding tolerance of 0.01 in monetary precision)
```

---

## 6. Edge Cases

### 6.1 Cross-Timezone Delivery Points

Different delivery points operate in different market time zones (e.g., DE_LU in Europe/Berlin, NO1 in Europe/Oslo -- same UTC offset but distinct IANA zones). The rollup period boundaries (MONTHLY, WEEKLY) must be computed in the delivery point's market-local time zone, not in UTC. A "January" rollup for DE_LU runs from 2026-01-01T00:00 CET (2025-12-31T23:00Z) to 2026-02-01T00:00 CET (2026-01-31T23:00Z). The dashboard must not naively use UTC month boundaries when querying rollup cells.

### 6.2 DST Transitions

Covered in AC-4.2 and AC-4.3. Additional edge: if the user filters Level 2 rollup grid to a single week that contains a DST transition, the weekly rollup cell's netMw must reflect the correct TWA using actual interval minutes (92, 96, or 100 per day), not a fixed 96-interval assumption. The S7 rollup materialization already handles this (FR-035), so the dashboard simply displays the pre-computed value.

### 6.3 Rollup Staleness and In-Flight Revaluation

If a market data update triggers settlement revaluation (MarketDataUpdated flow), there is a window where settlement cells have been updated but rollup cells have not yet been re-materialized. During this window, Level 2 (rollup grid) shows stale data while Level 4 (interval detail) shows current data. The dashboard should expose the `versionHash` or `computedAt` from rollup cells to signal staleness. It must NOT attempt to recompute rollups on the fly from settlement cells in the read path.

### 6.4 Backdated Corrections

When a backdated correction creates a new position ledger version (knownTo set on the old version, new version with a new knownFrom), Level 3 must show only the latest current-knowledge version by default. The corrected historical version is available via the audit trail toggle. Settlement cells and rollups will be recomputed by the revaluation cascade; the dashboard does not need to handle this specially beyond displaying the latest materialized state.

### 6.5 Cancellations

A cancelled position (status = CANCELLED) should be excluded from Level 3 by default. An optional "include cancelled" filter allows risk analysts to view cancelled positions for reconciliation purposes. Cancelled positions contribute zero to rollup aggregates (they are excluded from S5a materialization).

### 6.6 Multi-Currency Positions

A portfolio may contain positions priced in different currencies (e.g., EUR and GBP). At Level 1 (portfolio cards), aggregating PnL across currencies requires either:
- (a) Displaying separate cards per (portfolio, currency), or
- (b) Converting to a base currency using an FX rate.

This spec does not mandate FX conversion. Level 1 cards must group by currency. A portfolio with positions in both EUR and GBP produces two summary rows. See Open Question 8.3.

### 6.7 Multi-Tenant Isolation

Every query in every level must include the tenantId filter. The dashboard service must never return data belonging to a different tenant. In the library modules, this is enforced by passing tenantId explicitly to all service and repository calls. In the production hosting layer, RLS policies provide a second line of defense. The dashboard feature must not introduce any query path that omits tenantId.

### 6.8 Large Date Ranges

A request for portfolio cards across a 10-year PPA delivery window (e.g., 2026-2036) at MONTHLY granularity produces up to 120 rollup cells per delivery point per peak/off-peak split. With 5 delivery points and peak/off-peak, that is 1,200 cells -- manageable. However, if the user requests WEEKLY granularity for 10 years, that is approximately 5,200 cells per delivery point, or 52,000 cells total. The query must perform efficiently against the S7 rollup table. Pagination or range-limiting at the service level may be needed.

### 6.9 Leap Seconds and Sub-15-Minute Granularity

The platform currently operates at 15-minute settlement intervals (MIN_15). The TimeGranularity enum supports MIN_5 and MIN_30, but the dashboard spec assumes MIN_15 as the floor. If the platform extends to 5-minute settlement (as some EU markets are moving toward), Level 4 must adapt to show 288 intervals per day instead of 96. No dashboard-specific changes are needed beyond consuming whatever settlement cells exist.

### 6.10 Positions Spanning Multiple Months

A single position ledger entry's deliveryRange may span multiple months (e.g., a quarterly forward: 2026-01 to 2026-03). When the user drills from a monthly rollup cell for 2026-02 to Level 3, the query must return all position entries whose deliveryRange overlaps 2026-02, not only those starting in 2026-02. The PositionMonthSummary (which aggregates settlement cells by month) handles this correctly by computing per-month summaries per position.

---

## 7. Data Model Impact

### 7.1 Data Model Touchpoints

This feature is read-only and does not modify the domain model or persistence schema. It consumes existing structures:

| Subsystem | Domain Model / Record | Port Interface | Usage |
|---|---|---|---|
| S7 Rollups | `RollupCell` (record) | `RollupQueryService.findByRange()` | Levels 1 and 2. Portfolio cards are computed by aggregating rollup cells across delivery points. |
| S7 Rollups | `RollupRepository` | `findByRange()` | Underlying repository port for rollup queries. |
| S1 Position Ledger | `PositionLedgerEntry` | `PositionQueryService.findByDeliveryRange()` | Level 3. Filtered to current knowledge, ACTIVE status, specific portfolio. |
| S1 Position Ledger | `PositionMonthSummary` (record) | `PositionQueryService.monthlySummaryByPosition()` | Level 3. Pre-aggregated monthly summaries per position. |
| S5a Settlement | `SettlementCell` (domain model) | `SettlementQueryService.findByPosition()` | Level 4. 15-min interval detail for a selected position and date range. |
| Reference | `TimeGranularity` (enum) | N/A | WEEKLY, MONTHLY, YEARLY values used in Level 2 granularity filter. |

### 7.2 Service Interface Gaps

The existing service interfaces may need extensions to support this feature fully:

| Gap | Current State | Needed For |
|---|---|---|
| `RollupQueryService.findByRange` requires both `deliveryPointId` and `portfolioId` | Both parameters are mandatory (non-null) | Level 1 (portfolio cards) needs rollups across all delivery points for a portfolio. Level 2 cross-portfolio view needs rollups across all portfolios. Either make these parameters nullable (returning all when null) or add overloaded methods. |
| `PositionQueryService` lacks portfolio+month filter | `findByDeliveryRange` does not filter by portfolioId | Level 3 needs positions filtered by (portfolioId, deliveryMonth). A new method or query parameter is needed. |
| No "portfolio list" query | No service returns the set of portfolios with positions for a tenant | Level 1 needs to know which portfolios to render cards for. Either a dedicated query or derived from rollup cell distinct portfolioId values. |
| `PositionMonthSummary` lacks portfolioId and deliveryPointId | Record has positionId, tradeId, tradeLegId but not portfolioId or deliveryPointId | Level 3 grouping/display needs these. They can be obtained by joining with the PositionLedgerEntry, but it may be cleaner to include them in the summary record. |
| DAILY granularity rollups | S7 materializes WEEKLY, MONTHLY, YEARLY only | Level 2 "daily filter" -- see Open Question 8.2. |

### 7.3 Reference Data Dependencies

| Reference Data | Dependency |
|---|---|
| **Portfolio reference** | Portfolio ID, name, and metadata for card rendering. The portfolio master is assumed to exist in reference data (not yet specified in this repo). |
| **Delivery point reference** | Delivery point ID, name, market zone, time zone. Required for timezone-correct period boundary computation (Edge Case 6.1). |
| **Market calendar** | Peak/off-peak classification per interval (FR-026). Already consumed by S7 rollup materialization; the dashboard reads the pre-classified `isPeak` flag. |
| **Currency** | ISO 4217 currency codes. Already present on rollup cells and settlement cells. |

---

## 8. Open Questions

| # | Question | Impact | Suggested Owner |
|---|---|---|---|
| **8.1** | Should portfolios with zero positions (no ledger entries, no rollup cells) appear as empty cards on Level 1, or be omitted entirely? | UX decision. Omitting them is simpler but hides the fact that a portfolio exists with no activity. | Product / UX |
| **8.2** | Should the dashboard support DAILY granularity at Level 2? S7 rollup materialization currently produces WEEKLY, MONTHLY, YEARLY only. Options: (a) add DAILY to S7 materialization (storage cost), (b) compute daily aggregation on-the-fly from S5a settlement cells (latency cost, contradicts FR-090 materialization intent), (c) defer DAILY and document it as out of scope for V1. | Feature scope and S7 materialization changes. DAILY rollups would approximately 7x the WEEKLY cell count. | Functional analyst + solutions architect |
| **8.3** | How should multi-currency portfolios be handled at Level 1? Options: (a) separate cards per (portfolio, currency), (b) convert to tenant base currency using S4 FX rates, (c) show dominant currency with a warning. Option (b) requires an FX rate lookup path that does not currently exist in the dashboard read flow. | UX and data model. | Product / functional analyst |
| **8.4** | Should Level 3 (trade-level view) support bitemporal as-of queries (FR-007)? e.g., "show positions as they were known on 2026-03-15 for business date 2026-03-01". This is valuable for compliance and audit but adds query complexity. | Service interface extension (PositionQueryService.findAsOf already exists but is trade-specific, not portfolio-wide). | Functional analyst |
| **8.5** | What is the refresh/polling strategy for the dashboard? Options: (a) manual refresh button, (b) periodic polling (e.g., every 30s), (c) server-sent events / WebSocket push when rollup cells are re-materialized. The `versionHash` on RollupCell enables efficient change detection for (b) and (c). | UX and architecture. This is a presentation-layer concern, not a domain concern, but the domain must support efficient staleness detection. | Solutions architect |
| **8.6** | Should the dashboard expose forward mark values (forwardMarkValue on RollupCell) separately from settled values? Forward marks are ephemeral (D-3) and apply to undelivered intervals. The distinction matters for risk analysts comparing marked vs. settled PnL. | UX and PnL decomposition presentation. | Product / risk |
| **8.7** | For Level 2 cross-portfolio aggregation, should the dashboard service compute the cross-portfolio totals, or should the presentation layer aggregate the per-portfolio rollup cells? Computing server-side avoids TWA re-aggregation errors in the UI but adds a service method. FR-035 TWA rules mean that cross-portfolio netMw cannot be computed by simple averaging of per-portfolio netMw values -- it requires re-weighting by interval duration and MWh. | Correctness of MW aggregation across portfolios. | Solutions architect |
| **8.8** | The `RollupQueryService.findByRange` method signature requires `deliveryPointId` and `portfolioId`. Should these be made nullable (wildcard semantics) or should new overloaded methods be added? Nullable parameters in port interfaces are less explicit; overloads are more verbose but clearer. | Service interface design decision. | Solutions architect |
| **8.9** | Should Level 4 (interval detail) support filtering by peak/off-peak? The settlement cell does not currently carry an `isPeak` flag -- it would need to be derived from the market calendar at query time or joined from the interval dimension. | Query complexity and reference data join. | Functional analyst |
| **8.10** | What pagination strategy should be used for Level 4 when a position spans many months (e.g., a 10-year PPA drilled to a single month = ~2,976 rows, manageable; but if the user requests a full year = ~35,040 rows)? Should the query enforce a single-month scope, or allow arbitrary date ranges with pagination? | Performance and UX. | Product / solutions architect |
