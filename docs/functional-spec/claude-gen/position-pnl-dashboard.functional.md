# Functional Specification -- Position & PnL Dashboard

**Feature:** Position & PnL Dashboard (hierarchical drill-down with settlement and risk views)
**Version:** 3.0 (revised to incorporate forward-looking risk/trading perspective)
**Date:** 2026-08-12
**Status:** DRAFT -- pending review
**Spec references:** FR-035, FR-075, FR-086, FR-090, FR-105, D-1, D-3, D-6, D-11, D-12, S1, S5a, S5b, S6b, S7

---

## Context

Portfolio managers, traders, and risk analysts need a single dashboard that answers
two complementary questions:

1. **Settlement (backward-looking):** "What has been realized? What were the
   settled prices, volumes, and PnL for delivered intervals?"
2. **Risk / Trading (forward-looking):** "What is my open position? What is the
   current mark-to-market? Where is my forward exposure?"

A CTRM Position & Valuation system that only answers the first question is a back
office reporting tool. To serve the front office -- traders managing live risk,
portfolio managers tracking total portfolio value, and risk analysts monitoring
exposure -- the dashboard must combine BOTH perspectives into a unified view.

The platform materializes data across five subsystems that together support both
views:

| Subsystem | Role | Time Horizon | Persistence |
|-----------|------|--------------|-------------|
| **S7 Rollup Cells** | Coarse-grain aggregates (WEEKLY, MONTHLY, YEARLY) per (delivery_point, portfolio) with peak/off-peak split. Carries both `settledValue` (from S5a) and `forwardMarkValue` (from S5b). Per FR-090. | Past + Forward | Durable, versioned |
| **S1 Position Ledger** | Bitemporal trade-leg-grained source of truth. Per D-1, FR-030. | Full contract life | Bitemporal |
| **S5a Settlement Cells** | 15-minute interval-grained measures for delivered intervals with price, volume, amount, marketPrice, pnl. Per FR-070. | Past (delivered) | Bitemporal (knownFrom/knownTo) |
| **S5b Forward Marks** | Ephemeral current-state marks per position x interval for undelivered periods. Curve price x forecast volume through the price expression. Per FR-075, D-3. | Future (undelivered) | Ephemeral (overwrite) |
| **S6b Trade Interval Cache** | Pre-multiplied resolved volume per trade-leg x interval for the FULL delivery window (including forward). Per FR-086, D-12. | Past + Forward | Rebuildable cache |

**The two perspectives in tabular form:**

| | Settlement (Back Office) | Risk / Trading (Front Office) |
|---|---|---|
| Time horizon | Past -- settled/delivered intervals | Future -- unsettled forward periods |
| Volumes | Actuals (from S5a settlement cells) | Forecasts (from S6b trade interval cache) |
| Prices | Settled fixings, trade prices | Forward curves, marks (from S5b forward marks) |
| Key metric | Realized PnL | Unrealized Mark-to-Market |
| Update frequency | Batch / on settlement event | Near real-time (on curve update) |

**Key domain concepts:**

- **Realized PnL** = settlement cell pnl summed over delivered intervals (S5a/S7
  `settledValue` minus `marketValue`, or equivalently S7 `pnl`).
- **Unrealized MtM** = forward mark value summed over undelivered intervals (S5b
  marks aggregated into S7 `forwardMarkValue`). Represents current curve price x
  forecast volume for each open position.
- **Total Portfolio Value** = Realized PnL + Unrealized MtM. This is the headline
  metric for portfolio managers.
- **Open Position** = net MW exposure across all trades in a portfolio for future
  delivery periods. Derived from S6b resolved quantities for undelivered intervals.
- **Imbalance Exposure** = deviation between nominated, forecast, and actual
  volumes. Out of scope for v3.0 (requires nomination data not in this system);
  flagged as a future enhancement.

The dashboard surfaces these data layers through a four-level hierarchical
drill-down:

| Level | Question | Settlement Source | Risk Source |
|-------|----------|-------------------|-------------|
| L1 -- Portfolio Cards | "What is my total portfolio value?" | S7 rollups (settledValue, pnl) | S7 rollups (forwardMarkValue) |
| L2 -- Period Grid | "How does value distribute across time?" | S7 rollup cells (past periods) | S7 rollup cells (forward periods) |
| L3 -- Trade-Level View | "Which trades contribute?" | S1 position ledger | S1 position ledger + settlement status |
| L4 -- Interval Detail | "What are the interval-level details?" | S5a settlement cells (delivered days) | S5b forward marks + S6b volumes (forward days) |

The dashboard is **read-only**. It does not trigger any write operations, event
publications, or state changes. All data is pre-materialized by the existing
settlement, forward mark, and rollup pipelines (FR-100, FR-105).

---

## Actors

| Actor | Role in this feature |
|-------|---------------------|
| **Portfolio Manager** | Primary consumer. Views total portfolio value (realized + unrealized), drills into time periods and trades to understand performance drivers and forward exposure. |
| **Trader** | Monitors open position and MtM for portfolios they trade. Uses forward view to assess exposure before placing new trades. Drills to interval level to verify settlement pricing or inspect forward curve assumptions. |
| **Risk Analyst** | Uses L2 and L4 to verify mark-to-market values, spot stale marks, and identify concentration risk across delivery periods. Monitors staleness indicators on forward marks. |
| **Middle Office** | May use L3 and L4 to reconcile trade-level positions against settlement statements. Uses settlement status indicators to track the settled/forward boundary. |
| **Back Office** | Uses L4 day view (settled intervals only) to verify interval-level settlement amounts for invoicing. |

All actors operate within a single tenant boundary. Multi-tenant isolation is
enforced by the platform's tenant context mechanism; this feature does not introduce
any cross-tenant data access.

---

## Business Events

This dashboard is a **pure read feature**. It does not generate business events.
It consumes data produced by the following existing business events:

| Event | Trigger | Effect on Dashboard |
|-------|---------|-------------------|
| `SettlementComputed` | Settlement materialization job completes for a position | L4 settled day-view data becomes available or updated; L1/L2 data updates after rollup refresh |
| `SettlementRevaluationRequested` | Market data or volume supersession (delivered intervals) | Settlement cells recomputed; dashboard reflects updated values on next query |
| `ForwardMarkJob` completion | Curve tick, volume supersession, or batch cycle (undelivered intervals) | S5b forward marks overwritten; S7 `forwardMarkValue` updated on next rollup refresh; L1/L2/L4 forward data refreshes |
| `VolumeSuperseded` | New forecast published for an asset | S6b trade interval cache rebuilt for affected intervals; S5b marks re-struck; forward volume and MtM values update |
| `VolumePublished` | First publication of a volume series | S6b populated; S5b marks created for newly available forward intervals |
| `CurveTick` | Forward curve update | Triggers `ForwardMarkJob` for affected positions via dependency index (FR-103); S5b marks overwritten |
| Rollup refresh (FR-105 step 4) | Batch cycle | L1 portfolio cards and L2 rollup grid reflect current aggregates for both settled and forward values |
| Trade capture / amendment / cancellation | Upstream trade lifecycle | New or changed position ledger entries appear in L3; downstream settlement, forward mark, and rollup pipelines eventually update all levels |

**Dashboard refresh model:** The dashboard reads materialized data. It does not
subscribe to events directly. Freshness depends on the materialization pipeline
cadence.

For **settlement data** (backward-looking): freshness tracks `computedAt` on
settlement cells and `versionHash` on rollup cells.

For **forward mark data** (forward-looking): freshness tracks the `inputVersionSet`
on S5b marks (which records the curve version, volume version, FX version, and
expression version used to strike the mark). The UI should display:
- A "data as of" timestamp derived from the most recent mark strike.
- A staleness indicator when the mark's input versions differ from the current
  versions of the underlying curves or volumes (see AC-L1-08).

---

## Regulatory Mapping

| Regulation | Applicability | Notes |
|------------|--------------|-------|
| **REMIT** (Regulation 1227/2011) | **Indirect.** The dashboard displays data that is reportable (trade-level positions, settlement values). It does not itself generate REMIT reports. Timestamps displayed must be convertible to UTC for reporting purposes (FR-007). Forward marks displayed are ephemeral (FR-075) and are NOT reportable under REMIT -- only the EOD struck mark (S5c) is the EMIR-reportable valuation. | No new reporting obligation. |
| **EMIR** | **Indirect.** The unrealized MtM displayed on the dashboard is derived from S5b forward marks, which feed the EOD struck mark (S5c, FR-079). S5c is the EMIR daily valuation. The dashboard does NOT display S5c; it displays the current (potentially intraday) S5b mark, which is for trading/risk purposes only, not regulatory valuation. | The dashboard must not be misconstrued as showing the official EMIR mark. A label such as "Current MtM (indicative)" should distinguish from the official EOD mark. |
| **MiFID II** (RTS 22) | **Indirect.** L3 trade-level view shows data that feeds MiFID II transaction reports. The dashboard does not itself report. | The L3 view must show tradeId and tradeLegId to support cross-reference with RTS 22 reports. |

No new regulatory obligations arise from this feature. The dashboard displays
existing regulated data in a consolidated view. The distinction between indicative
forward marks (S5b, displayed) and official marks (S5c, not displayed) must be
clearly communicated in the UI to avoid regulatory confusion.

---

## Acceptance Criteria

### Level 1 -- Portfolio Cards

**AC-L1-01: Portfolio card rendering with realized and unrealized split**

```
Given a tenant "TN_0042" with portfolio "WIND_DE"
  And rollup cells (S7) exist at MONTHLY granularity for "WIND_DE"
    for the range 2026-01-01 to 2027-12-31
  And rollup cells for past periods (before 2026-08-12) carry
    settledValue, marketValue, and pnl from S5a settlement cells
  And rollup cells for future periods (2026-09 onward) carry
    forwardMarkValue from S5b forward marks
  And the current month (2026-08) is a transition month with both
    settledValue (for delivered days) and forwardMarkValue (for undelivered days)
When the portfolio manager opens the Position & PnL Dashboard
Then the portfolio card for "WIND_DE" displays:
  - Realized PnL: sum of pnl from all rollup cells in the date range
  - Unrealized MtM: sum of forwardMarkValue from all rollup cells in the date range
  - Total Portfolio Value: Realized PnL + Unrealized MtM
  - Net MW (settled): TWA of netMw from settled rollup cells (FR-035)
  - Net MW (forward): TWA of netMw from forward rollup cells
  - Net MWh (settled): sum of netMwh from settled rollup cells
  - Net MWh (forward): sum of netMwh from forward rollup cells
  And the realized and unrealized components are visually separated
    (e.g., distinct sections or color coding)
  And the total portfolio value is prominently displayed as the headline metric
```

**AC-L1-02: Multi-currency portfolio cards**

```
Given portfolio "CROSS_BORDER" has rollup cells in EUR and GBP
When the dashboard renders the portfolio card
Then separate summary rows are shown per currency
  And each currency subtotal shows its own realized PnL, unrealized MtM,
    and total portfolio value
  And no cross-currency aggregation is performed at this level
  And the card clearly labels each currency subtotal
```

**AC-L1-03: Empty portfolio**

```
Given portfolio "NEW_PORTFOLIO" exists in reference data
  But no rollup cells exist for this portfolio
When the dashboard renders the portfolio cards
Then a card for "NEW_PORTFOLIO" is shown with all numeric values as zero or null
  And the card is visually distinct (e.g., greyed out) to indicate no data
```

**AC-L1-04: Date range filter on portfolio cards**

```
Given rollup cells exist for portfolio "WIND_DE" across 2026-01 through 2027-12
When the portfolio manager selects a date range of 2026-08-01 to 2026-12-31
Then the portfolio card aggregates only rollup cells whose period falls within
  the selected range
  And periods partially overlapping the range boundary are included if their
    periodStart falls within the range
  And both realized PnL and unrealized MtM respect the date range filter
```

**AC-L1-05: Portfolio with only forward exposure (no settlement yet)**

```
Given portfolio "NEW_WIND" has trades with delivery starting 2027-01
  And no settlement cells exist (delivery has not begun)
  And forward marks (S5b) have been struck and rolled into S7 forwardMarkValue
When the portfolio card renders
Then Realized PnL = 0 (or null, with a "no settled data" indicator)
  And Unrealized MtM shows the sum of forwardMarkValue
  And Total Portfolio Value = Unrealized MtM
  And the card clearly indicates this is entirely forward/mark-to-market exposure
```

**AC-L1-06: Portfolio with only settled history (fully delivered)**

```
Given portfolio "LEGACY_SOLAR" has trades that completed delivery in 2025-12
  And all intervals are settled
  And no forward marks exist (no undelivered intervals)
When the portfolio card renders
Then Unrealized MtM = 0 (or null)
  And Realized PnL shows the sum of pnl from settled rollup cells
  And Total Portfolio Value = Realized PnL
```

**AC-L1-07: Open position display on portfolio card**

```
Given portfolio "WIND_DE" has trades delivering through 2027-12
  And S6b trade interval cache contains resolved quantities for undelivered intervals
When the portfolio card renders
Then it displays "Open Position" as the net MW exposure for the next delivery month
  And the open position is derived from S6b resolved quantities:
    sum of signed resolvedQty across all trade-legs in the portfolio
    for the next undelivered month
  And the value is displayed alongside the MtM figures
```

**AC-L1-08: Staleness indicator on forward marks**

```
Given portfolio "WIND_DE" has forward marks struck at 14:00 UTC on 2026-08-11
  And the forward curve for DE_LU has been updated at 09:00 UTC on 2026-08-12
  But forward marks have not yet been re-struck
When the portfolio card renders
Then a staleness indicator is displayed on the Unrealized MtM value
  And the indicator shows the mark strike timestamp (2026-08-11T14:00Z)
  And the indicator warns that underlying curves have been updated since the mark
  And the staleness is determined by comparing the mark's inputVersionSet
    against the current versions of the referenced market data series
```

### Level 2 -- Period Grid

**AC-L2-01: Monthly rollup grid with settled and forward periods**

```
Given the portfolio manager clicks on the "WIND_DE" portfolio card
  And the current date is 2026-08-12
When the rollup grid loads with granularity = MONTHLY
Then the grid displays one row per rollup cell for portfolio "WIND_DE"
  And columns are: periodStart, periodEnd, deliveryPointId, portfolioId,
    periodStatus, netMw, netMwh, price, marketPrice, settledValue,
    marketValue, pnl, forwardMarkValue, totalValue, currency
  And rows are sorted by periodStart ascending
  And each row carries a periodStatus indicator:
    - "SETTLED" for periods entirely in the past (all intervals settled)
    - "TRANSITION" for the current period (partially settled, partially forward)
    - "FORWARD" for periods entirely in the future (no settlement cells)
  And the totalValue column = settledValue + forwardMarkValue (or pnl + forwardMarkValue
    depending on the chosen value metric)
  And settled periods show pnl derived from S5a; forward periods show forwardMarkValue
    derived from S5b
```

**AC-L2-02: Visual distinction between settled and forward periods**

```
Given the rollup grid is displayed for portfolio "WIND_DE"
  And rows span 2026-01 through 2027-12
When the grid renders
Then settled periods (2026-01 through 2026-07) are visually distinct from
  forward periods (2026-09 through 2027-12)
  And the transition period (2026-08) is visually distinct from both
  And the distinction uses a consistent visual convention (e.g., background color,
    row border, or icon) that is defined in the UI specification
  And forward-period rows show forwardMarkValue prominently and settledValue
    as null or zero
  And settled-period rows show settledValue and pnl prominently and
    forwardMarkValue as null or zero
```

**AC-L2-03: Granularity filter (WEEKLY, MONTHLY, YEARLY)**

```
Given the rollup grid is displayed for portfolio "WIND_DE"
When the portfolio manager switches granularity from MONTHLY to WEEKLY
Then the grid reloads with S7 rollup cells at WEEKLY granularity
  And row count increases (approximately 4x for monthly-to-weekly)
  And the aggregation rules remain consistent:
    netMw = TWA, netMwh = sum, amounts = sum (FR-035)
  And settled/transition/forward status is computed per row based on
    whether the row's period is before, overlapping, or after the current date
```

**AC-L2-04: Multi-portfolio rollup grid**

```
Given the portfolio manager selects portfolios "WIND_DE" and "SOLAR_ES"
When the rollup grid loads
Then rollup cells for both portfolios are displayed
  And cells are distinguishable by portfolioId column
  And no cross-portfolio aggregation is performed
    (each row retains its portfolio identity per FR-090)
```

**AC-L2-05: Peak/off-peak split visibility**

```
Given rollup cells are materialized with isPeak = true and isPeak = false
  per FR-090
When the rollup grid is displayed
Then the grid either:
  (a) shows separate rows for peak and off-peak, or
  (b) provides a toggle to filter or merge peak/off-peak
  And the display convention is stated in the UI specification
  And peak/off-peak applies to both settled and forward rows
```

**AC-L2-06: Granularity hierarchy — upward-only aggregation**

The granularity hierarchy is strictly upward-only. S7 materializes each
granularity independently from the S5a 15-minute settlement cells (the finest
source of truth). Wider granularities are never disaggregated into finer ones.

```
Hierarchy (each level aggregated directly from S5a 15-min cells):

  S5a 15-min cells (source of truth)
    ├── DAILY   ← TWA/sum of 15-min cells within each calendar day
    ├── WEEKLY  ← TWA/sum of 15-min cells within each ISO week (Mon–Sun)
    ├── MONTHLY ← TWA/sum of 15-min cells within each calendar month
    └── YEARLY  ← TWA/sum of 15-min cells within each calendar year

Rules:
  - A wider granularity NEVER contains finer-grained data.
    MONTHLY rollups cannot produce WEEKLY or DAILY breakdowns.
  - The UI MAY offer sub-daily views (15/30/60-min) at L4 by querying
    S5a cells directly — these are NOT S7 rollups.
  - If a requested granularity has no materialized data for a period,
    the grid shows an empty state. It does NOT attempt on-demand
    aggregation from a different rollup granularity.
```

```
Given rollup cells exist at MONTHLY granularity but not at WEEKLY
  for portfolio "WIND_DE" in 2028
When the portfolio manager selects WEEKLY granularity for 2028
Then the grid displays an appropriate empty state message
  And does NOT disaggregate MONTHLY cells into WEEKLY
  And does NOT compute WEEKLY on-the-fly from DAILY rollups
    (each granularity is pre-materialized independently from S5a)
```

**AC-L2-07: Forward curve price display in forward periods**

```
Given a forward rollup cell for portfolio "WIND_DE", delivery month 2027-03
  And the forwardMarkValue was computed using the DE_LU Base Year-Ahead
    forward curve at version V42
When the rollup grid renders this row
Then the forwardMarkValue is displayed
  And a tooltip or detail popover shows:
    - Curve version used (e.g., "DE_LU Base YA v42")
    - Mark strike timestamp
    - Volume version used (forecast version)
  And the user can identify which market data assumptions underlie the value
```

### Level 3 -- Trade-Level View

**AC-L3-01: Position contribution view for a portfolio-month**

```
Given the portfolio manager clicks on the MONTHLY rollup row
  for portfolio "WIND_DE", period 2026-08 (periodStart = 2026-07-31T22:00:00Z)
When the trade-level view loads
Then it displays one row per active PositionLedgerEntry from S1
  where portfolioId = "WIND_DE"
  and deliveryRange overlaps the month 2026-08 (CET/CEST day boundaries)
  and knownTo IS NULL (current knowledge)
  and status = "ACTIVE"
  And each row shows BOTH position metadata AND aggregated volume/value data:

    Position metadata (from S1):
      tradeId, tradeLegId, tradeVersion, deliveryStart, deliveryEnd,
      quantity (contractual nominal), volumeUnit, deliveryPointId,
      deliveryStatus (SETTLED / PARTIAL / FORWARD)

    Settled actuals (aggregated from S5a settlement cells for this position-month):
      settledMw:    TWA of volumeMw across settled intervals (FR-035)
      settledMwh:   sum of volumeMwh across settled intervals
      avgPrice:     volume-weighted average (settledValue / settledMwh)
      settledValue: sum of amount across settled intervals
      marketValue:  sum of marketAmount across settled intervals
      realizedPnl:  sum of pnl across settled intervals

    Forward forecast (aggregated from S6b trade interval cache for this position-month):
      forwardMw:    TWA of resolvedQty across unsettled intervals (FR-035)
      forwardMwh:   sum of resolvedEnergy across unsettled intervals
      forwardMarkValue: sum of S5b mark values for unsettled intervals
      unrealizedMtm: forwardMarkValue (or null if no marks struck)

    currency (from S5a or S5b)

  And rows are sorted by tradeId, tradeLegId
  And the contractual quantity is shown for reference but is secondary
    to the actual/forecast volume columns
  And for variable-profile trades (wind/solar PPAs), the settled/forward MW
    will differ from the contractual quantity — this is expected and correct
  And deliveryStatus is derived from the presence/absence of S5a and S6b data:
    - "SETTLED" if S5a cells cover all intervals in this position-month
    - "PARTIAL" if both S5a and S6b data exist for this position-month
    - "FORWARD" if only S6b data exists (no settlement cells)
```

**AC-L3-02: Multiple delivery points within a portfolio-month**

```
Given portfolio "WIND_DE" has positions at delivery points "DE_LU" and "DE_AT"
  for delivery month 2026-08
When the trade-level view loads for "WIND_DE" / 2026-08
Then entries for both delivery points are displayed
  And the deliveryPointId column distinguishes them
```

**AC-L3-03: Superseded and cancelled entries excluded**

```
Given trade T-7788 has been amended (original entry has status = "SUPERSEDED")
  And a newer version exists with status = "ACTIVE"
When the trade-level view loads
Then only the ACTIVE entry is displayed
  And SUPERSEDED and CANCELLED entries are not shown
  And the tradeVersion column reflects the current version number
```

**AC-L3-04: Positions spanning month boundaries**

```
Given trade T-9900 has deliveryStart = 2026-07-15 and deliveryEnd = 2026-09-15
  And the position ledger contains delivery-month blocks for 2026-07, 2026-08,
    and 2026-09 per D-1
When the trade-level view loads for portfolio-month 2026-08
Then only the 2026-08 delivery-month block for T-9900 is shown
  And the deliveryStart/deliveryEnd on that block reflect the month-block
    boundaries, not the full trade delivery range
```

**AC-L3-05: Long-dated PPA showing forward delivery months**

```
Given trade T-7788 is a 10-year wind PPA delivering 2026-01 through 2035-12
  And the portfolio manager selects delivery month 2030-06 from the L2 grid
When the trade-level view loads for "WIND_DE" / 2030-06
Then the position ledger entry for T-7788's 2030-06 delivery-month block is shown
  And deliveryStatus = "FORWARD" (entirely undelivered)
  And the view indicates that drill-down to L4 will show forward mark data,
    not settlement data
```

### Level 4 -- Interval Detail (Settled Day View)

**AC-L4-01: Settled day view at 15-minute granularity**

```
Given the user selects a delivered day 2026-08-10
  from the trade-level view or the rollup grid
When the interval detail view loads with sub-daily granularity = MIN_15
Then the view displays settlement cells (S5a) for that CET/CEST day
  And the columns are: intervalStart, intervalEnd, volumeMw, volumeMwh,
    price, amount, marketPrice, marketAmount, pnl, currency
  And there are exactly 96 rows for a normal day (00:00-00:15 through 23:45-00:00 CET)
  And rows are sorted by intervalStart ascending
  And the view header indicates "Settlement Data (Realized)"
```

**AC-L4-02: Settled day view at 30-minute aggregation**

```
Given the user selects delivered day 2026-08-10 with sub-daily granularity = MIN_30
When the interval detail view loads
Then 48 rows are displayed (each spanning 30 minutes)
  And volumeMw for each 30-minute row is the time-weighted average
    of the two constituent 15-minute cells (FR-035)
  And volumeMwh for each 30-minute row is the sum
    of the two constituent 15-minute cells (FR-035)
  And amount, marketAmount, pnl are sums of the constituent cells
  And price is volume-weighted average (sum(amount) / sum(volumeMwh))
  And marketPrice is volume-weighted average (sum(marketAmount) / sum(volumeMwh))
```

**AC-L4-03: Settled day view at 60-minute aggregation**

```
Given the user selects delivered day 2026-08-10 with sub-daily granularity = HOURLY
When the interval detail view loads
Then 24 rows are displayed (each spanning 60 minutes)
  And the same aggregation rules as AC-L4-02 apply
    (TWA for MW, sum for MWh and amounts)
  And each row aggregates 4 constituent 15-minute cells
```

**AC-L4-04: Settled day view scoped to a single position**

```
Given the user drills from L3 (trade-level view) into L4 for trade T-7788,
  tradeLeg "LEG-1", delivery day 2026-08-10
When the interval detail view loads
Then only settlement cells for position T-7788/LEG-1 are shown
  And the positionId filter matches the PositionLedgerEntry.id for that
    trade-leg and delivery-month block
```

**AC-L4-05: Settled day view scoped to a portfolio-day**

```
Given the user drills from L2 (rollup grid) into L4 for portfolio "WIND_DE",
  delivery day 2026-08-10
When the interval detail view loads
Then settlement cells for ALL positions in portfolio "WIND_DE"
  for that day are shown
  And the display may either:
    (a) show individual position rows, or
    (b) net across positions per interval
      (with netMw = sum of signed volumeMw, netMwh = sum, etc.)
  And the chosen display convention is stated in the UI specification
```

### Level 4 -- Interval Detail (Forward Day View)

**AC-L4-10: Forward day view at 15-minute granularity**

```
Given the user selects a future (undelivered) day 2026-09-15
  from the rollup grid or trade-level view
When the interval detail view loads with sub-daily granularity = MIN_15
Then the view displays forward data for that CET/CEST day:
  - volumeMw, volumeMwh: from S6b trade interval cache (resolvedQty, resolvedEnergy)
    for the relevant trade-leg(s) in the selected portfolio/position scope
  - curvePrice: the forward curve price used in the S5b mark calculation
  - markValue: the S5b forward mark value per interval
  - currency: from the forward mark
  And there are exactly 96 rows for a normal day
  And rows are sorted by intervalStart ascending
  And the view header indicates "Forward Mark Data (Unrealized)"
  And the view does NOT show settlement columns (amount, marketAmount, pnl)
    because no settlement exists for forward intervals
```

**AC-L4-11: Forward day view showing S6b volume breakdown**

```
Given the user drills into a forward day for trade T-7788, tradeLeg "LEG-1"
When the interval detail view loads
Then for each 15-minute interval, the view shows:
  - resolvedQty (MW): from S6b, the pre-multiplied volume for this trade-leg
  - resolvedEnergy (MWh): from S6b, the pre-multiplied energy for this trade-leg
  - multiplier: from S6b, the allocation multiplier applied
  - seriesKey: from S6b, identifying the underlying volume series
  And these values represent the forecast generation/consumption assumption
    used to compute the forward mark
  And if S6b data does not exist for this trade-leg/interval (cache not populated),
    the row shows "volume unavailable" and markValue is null
```

**AC-L4-12: Forward day view with no forward marks**

```
Given the user selects a future day 2027-06-15
  And no forward marks (S5b) have been struck for positions in portfolio "WIND_DE"
    for this day (e.g., forward curve not yet available for this delivery period)
  But S6b trade interval cache contains volume data for this day
When the interval detail view loads
Then volume data (from S6b) is displayed: resolvedQty, resolvedEnergy per interval
  And markValue is null for all intervals
  And a banner indicates "Forward curve not available -- MtM cannot be computed"
  And the view is NOT empty (volume forecast is still useful information)
```

**AC-L4-13: Forward day view with no S6b data**

```
Given the user selects a future day 2028-01-15
  And neither S5b forward marks nor S6b trade interval cache data exists
    for positions in portfolio "WIND_DE" for this day
When the interval detail view loads
Then the view displays an empty state with a message:
  "No volume forecast or forward marks available for this delivery day"
  And the view does NOT display an error
```

**AC-L4-14: Forward day view aggregation (30-min, 60-min)**

```
Given the user views a forward day with sub-daily granularity = HOURLY
When the interval detail view loads
Then S6b volumes are aggregated: resolvedQty = TWA across constituent intervals,
  resolvedEnergy = sum (FR-035)
  And S5b markValue is summed across constituent intervals
  And curvePrice is volume-weighted average (sum(markValue) / sum(resolvedEnergy))
  And the aggregation rules mirror those for settled day view
```

### Level 4 -- Interval Detail (Month View)

**AC-L4-06: Month view showing daily aggregates with settled/forward split**

```
Given the user is on L4 without a specific day selected
  And the selected portfolio-month is "WIND_DE" / 2026-08
  And today is 2026-08-12
When the interval detail view loads in month mode
Then the view displays one row per CET/CEST delivery day within 2026-08
  And there are 31 rows (2026-08-01 through 2026-08-31)
  And each row carries a dayStatus:
    - "SETTLED" for days 2026-08-01 through 2026-08-11
    - "TODAY" for 2026-08-12 (partially settled depending on gate closure)
    - "FORWARD" for days 2026-08-13 through 2026-08-31
  And settled rows show: volumeMw (TWA), volumeMwh (sum), price, amount,
    marketPrice, marketAmount, pnl -- from S5a settlement cells
  And forward rows show: volumeMw (TWA from S6b), volumeMwh (sum from S6b),
    curvePrice (volume-weighted avg from S5b), markValue (sum from S5b) -- no pnl
  And the columns adapt based on day status or show both settled and forward
    columns with nulls where inapplicable
```

**AC-L4-07: Month view with partial data (transition month)**

```
Given today is 2026-08-15
  And settlement cells exist for 2026-08-01 through 2026-08-14
  And forward marks exist for 2026-08-16 through 2026-08-31
  And 2026-08-15 has partial settlement (some intervals settled, some forward)
When the month view loads for 2026-08
Then rows for 2026-08-01 through 2026-08-14 show settled data with dayStatus = "SETTLED"
  And the row for 2026-08-15 shows dayStatus = "TODAY" or "PARTIAL"
  And rows for 2026-08-16 through 2026-08-31 show forward mark data
    with dayStatus = "FORWARD"
  And the total row at the bottom shows both realized sum and unrealized sum,
    clearly separated
```

**AC-L4-08: Transition from month view to day view (settled vs. forward)**

```
Given the user is viewing the month view for "WIND_DE" / 2026-08
When the user clicks on a settled day row (2026-08-10)
Then the view switches to the settled day view (AC-L4-01 through AC-L4-05)
  And the sub-daily granularity filter (15min / 30min / 60min) becomes available

When the user clicks on a forward day row (2026-08-20)
Then the view switches to the forward day view (AC-L4-10 through AC-L4-14)
  And the sub-daily granularity filter (15min / 30min / 60min) becomes available
  And the view header clearly indicates "Forward Mark Data (Unrealized)"
```

### Cross-Level: Total Portfolio Value Calculation

**AC-TOTAL-01: Total portfolio value consistency**

```
Given portfolio "WIND_DE" has:
  - Settled months 2026-01 through 2026-07 with pnl values summing to EUR 1,234,567
  - Current month 2026-08 with settled pnl = EUR 89,000 and forwardMarkValue = EUR 45,000
  - Forward months 2026-09 through 2027-12 with forwardMarkValue summing to EUR 3,456,789
When the portfolio card (L1) renders
Then Total Portfolio Value = EUR 1,234,567 + EUR 89,000 + EUR 45,000 + EUR 3,456,789
    = EUR 4,825,356
  And the breakdown is:
    Realized PnL = EUR 1,234,567 + EUR 89,000 = EUR 1,323,567
    Unrealized MtM = EUR 45,000 + EUR 3,456,789 = EUR 3,501,789
  And the sum of L2 grid pnl + forwardMarkValue values equals the L1 totals
    (no rounding discrepancy across levels)
```

**AC-TOTAL-02: Forward mark not double-counted**

```
Given a position has delivery month 2026-08 (transition month)
  And settlement cells (S5a) exist for 2026-08-01 through 2026-08-11
  And forward marks (S5b) exist for 2026-08-12 through 2026-08-31
  And the S7 rollup cell for 2026-08 carries both settledValue and forwardMarkValue
When the dashboard renders
Then the settled intervals are counted in Realized PnL
  And the forward intervals are counted in Unrealized MtM
  And no interval is counted in both
  (The rollup materialization pipeline is responsible for this non-overlap;
   the dashboard reads and displays what the rollup provides)
```

---

## DST Handling

This feature displays time-series data at interval granularity. DST handling is
**critical** and must be addressed at every level, for both settlement and forward
data.

### Interval counts on DST transition days

**Spring-forward day** (last Sunday of March; e.g., 2027-03-28):
- The CET/CEST day has 23 hours = **92 quarter-hour intervals**.
- The hour 02:00--03:00 CET does not exist.
- L4 day view at MIN_15: 92 rows. At MIN_30: 46 rows. At HOURLY: 23 rows.
- Applies to both settled day view (S5a) and forward day view (S5b/S6b).

**Fall-back day** (last Sunday of October; e.g., 2026-10-25):
- The CET/CEST day has 25 hours = **100 quarter-hour intervals**.
- The hour 02:00--03:00 CET occurs twice (once in CEST, once in CET).
- L4 day view at MIN_15: 100 rows. At MIN_30: 50 rows. At HOURLY: 25 rows.
- Applies to both settled day view (S5a) and forward day view (S5b/S6b).

**Normal days:** 96 intervals at MIN_15, 48 at MIN_30, 24 at HOURLY.

### The missing hour (spring-forward)

- Settlement cells (S5a) for the non-existent hour 02:00--03:00 CET will not exist
  (the interval generator correctly produces 92 intervals for this day).
- S5b forward marks and S6b trade interval cache entries for this hour will also
  not exist (same interval generator).
- The L4 day view grid (both settled and forward) must skip this hour. The UI must
  display a visual indicator (e.g., a collapsed row or banner) explaining that this
  hour does not exist due to DST spring-forward.
- No user action references a non-existent interval. If a query is constructed that
  includes this hour, zero rows are returned -- this is correct behavior, not an
  error.

### The duplicate hour (fall-back)

- The two distinct hours 02:00--03:00 are disambiguated by UTC offset:
  - First occurrence: 02:00 CEST (UTC+02:00), stored as 00:00--01:00 UTC
  - Second occurrence: 02:00 CET (UTC+01:00), stored as 01:00--02:00 UTC
- The L4 day view grid (both settled and forward) must label these distinctly.
  Convention:
  - Option A: "02:00 CEST" / "02:00 CET"
  - Option B: "02:00A" / "02:00B"
  - The chosen convention must be consistent across the platform.
- Sub-daily aggregation (30min, 60min) on fall-back day: the 8 quarter-hour
  intervals spanning the duplicate hour (4 in CEST, 4 in CET) aggregate into
  2 distinct hourly rows or 4 distinct 30-min rows. They must NOT be collapsed
  into a single row.
- This applies equally to S5a settlement cells, S5b forward marks, and S6b
  trade interval cache entries.

### Day boundaries for aggregation

- A "day" is a CET/CEST day, not a UTC day.
- Normal day boundaries in UTC:
  - CET (winter): 23:00 UTC previous calendar day to 23:00 UTC
  - CEST (summer): 22:00 UTC previous calendar day to 22:00 UTC
- Spring-forward day: 23:00 UTC (prev) to 22:00 UTC (23 hours)
- Fall-back day: 22:00 UTC (prev) to 23:00 UTC (25 hours)
- L4 month view daily aggregation: each row's intervalStart/intervalEnd must
  reflect the correct UTC boundaries for that CET/CEST day.
- L1/L2 date range filters: when the user selects "August 2026", the UTC
  boundaries are 2026-07-31T22:00:00Z to 2026-08-31T22:00:00Z (August is
  entirely within CEST).
- These rules apply identically when querying S5a (settled), S5b (forward marks),
  or S6b (trade interval cache).

### Gate closure alignment

Not directly applicable to this dashboard (read-only feature). However, if the
dashboard ever displays "data freshness" timestamps, "last settlement update"
times, or "mark strike timestamps", those must be displayed in both CET/CEST
and UTC.

### REMIT reporting timestamps

The dashboard itself does not generate REMIT reports. All timestamps are stored as
UTC (`Instant` / `TIMESTAMP WITH TIME ZONE`) per platform convention. The
presentation layer displays times in CET/CEST with UTC shown alongside for
time-critical views (L4 interval detail). Forward mark `inputVersionSet` timestamps
are stored in UTC.

### Persistence layer implications (hand off to solutions-architect)

- All settlement cell, forward mark, and trade interval cache `intervalStart`/
  `intervalEnd` are stored as UTC.
- Queries for "delivery day" must compute correct UTC boundaries for the requested
  CET/CEST day, including DST-aware boundary shifts.
- The existing `MarketCalendar` service is the sole authority for interval
  generation and day-boundary computation. Dashboard queries must use it to
  translate user-selected dates to UTC ranges.

### Presentation layer implications (hand off to UI architect)

- Display times in both CET/CEST and UTC for L4 interval detail views (both
  settled and forward).
- On spring-forward day, the interval grid skips 02:00--03:00 CET with a visual
  indicator explaining why.
- On fall-back day, the duplicate hour is labeled distinctly per the platform
  convention.
- Date range pickers (L1, L2 filters) must convert local CET/CEST dates to UTC
  boundaries correctly on transition days.
- The L4 month view row for a DST transition day must show the correct interval
  count (92 or 100) in any tooltip or detail display, not a hardcoded 96.

---

## Data Model Impact

### Existing structures consumed (no schema changes)

| Structure | Used by Level(s) | Access pattern |
|-----------|-------------------|----------------|
| `RollupCell` (S7) | L1 (realized + unrealized), L2, L4 (month view fallback) | `RollupRepository.findByRange(tenantId, deliveryPointId, portfolioId, rangeStart, rangeEnd, granularity)` |
| `PositionLedgerEntry` (S1) | L3 (position metadata) | `PositionLedgerRepository.findAllByDeliveryRange(tenantId, deliveryStart, deliveryEnd)` -- filtered by portfolioId in application layer |
| `SettlementCell` (S5a) | L3 (settled actuals per position), L4 settled day view, L4 month view (settled days) | `SettlementCellRepository.findByPosition(tenantId, positionId, rangeStart, rangeEnd)` -- aggregated per position for L3 |
| `ForwardMark` (S5b) | L1 (via S7), L3 (unrealized MtM per position), L4 forward day view | `ForwardMarkStore.getRange(tenantId, positionId, rangeStart, rangeEnd)` |
| `TradeIntervalCacheEntity` (S6b) | L1 (open position), L3 (forward volume per position), L4 forward day view | By `tradeLegId` + interval range; or by `tenantId` + interval range for portfolio scope |

### New or modified query capabilities needed

**Q-1: Portfolio-level rollup aggregation.**
L1 portfolio cards require aggregating S7 rollup cells across all delivery points
and all periods for a given portfolio and date range, now returning both
`settledValue`/`pnl` (realized) and `forwardMarkValue` (unrealized). The current
`RollupQueryService.findByRange()` requires a `deliveryPointId` parameter. Options:
- (a) Add a new method `findByPortfolio(tenantId, portfolioId, rangeStart,
  rangeEnd, granularity)` that omits the delivery point filter.
- (b) Allow `deliveryPointId = null` to mean "all delivery points."
- (c) Query all delivery points in the application layer and aggregate.

**Q-2: Position ledger filtered by portfolioId.**
L3 requires filtering position ledger entries by portfolioId within a delivery
range. The current `PositionLedgerRepository.findAllByDeliveryRange()` does not
filter by portfolio. Options:
- (a) Add `findByPortfolioAndDeliveryRange(tenantId, portfolioId, deliveryStart,
  deliveryEnd)`.
- (b) Filter in the application layer after fetching all entries for the delivery
  range. Acceptable for small result sets; may be problematic for tenants with many
  positions.

**Q-3: Settlement cells for a portfolio-day.**
L4 settled day view when scoped to a portfolio (not a single position) requires
fetching settlement cells across all positions in that portfolio for a single day.
The current `SettlementCellRepository.findByPosition()` takes a single `positionId`.
Options:
- (a) Add `findByPortfolioAndDateRange(tenantId, portfolioId, rangeStart,
  rangeEnd)` -- requires a join to the position ledger or a denormalized
  `portfolioId` on the settlement cell.
- (b) First query L3 to get position IDs, then query S5a per position. Acceptable
  if position count per portfolio-month is small (typically 10--50).

**Q-9: Per-position volume and value summaries for L3.**
L3 now shows aggregated settled actuals and forward forecasts per position (not just
the raw contractual quantity from S1). This requires, for each position in the
portfolio-month:
- Settled: aggregate S5a settlement cells → settledMw (TWA), settledMwh (sum),
  settledValue (sum of amount), marketValue (sum of marketAmount), realizedPnl
  (sum of pnl), avgPrice (settledValue / settledMwh).
- Forward: aggregate S6b trade interval cache → forwardMw (TWA of resolvedQty),
  forwardMwh (sum of resolvedEnergy). Plus sum of S5b forward marks →
  forwardMarkValue.
Options:
- (a) Backend service method: a dedicated `PositionContributionQueryService` that,
  given a list of position IDs and a month range, returns per-position summary
  records by joining S5a, S5b, and S6b. This keeps FR-035 aggregation rules
  server-side and avoids N+1 queries from the UI.
- (b) Composite query: L3 fetches position IDs from S1, then issues parallel
  queries to S5a (per position), S6b (per trade-leg), and S5b (per position) and
  aggregates in the application layer. Acceptable for small position counts
  (10--50 per portfolio-month).
- Recommendation: option (a) for correctness and performance. The aggregation
  rules (FR-035 TWA for MW, sum for MWh/amounts, volume-weighted avg for price)
  are domain logic and should live in the domain service layer.

**Q-4: Daily aggregation for L4 month view.**
S7 currently materializes WEEKLY, MONTHLY, YEARLY granularities. DAILY is not
materialized. For L4 month view:
- (a) Add DAILY to S7 materialization (FR-105 step 4). This is the cleanest
  solution but increases rollup storage.
- (b) Aggregate S5a settlement cells (for settled days) and S5b forward marks
  (for forward days) on the fly, grouped by CET/CEST day boundaries.
- (c) Hybrid: materialize DAILY rollups for delivered months only; aggregate on the
  fly for the current/forward months.

**Q-5: 30-minute and 60-minute aggregation for L4 day view.**
S5a stores 15-minute cells. S5b stores 15-minute marks. S6b stores 15-minute
intervals. Aggregation to 30min or 60min requires:
- (a) Backend aggregation: add a query or service method that groups cells/marks
  by 30-min or 60-min boundaries and applies FR-035 rules (TWA for MW, sum
  for MWh/amounts).
- (b) Frontend aggregation: return 15-min data to the client and let the UI
  aggregate. Acceptable for a single day (max 100 cells), but pushes domain logic
  (FR-035) to the client.

**Q-6: Forward marks for a portfolio-day (new).**
L4 forward day view when scoped to a portfolio requires fetching S5b marks across
all positions in that portfolio for a single day. The current
`ForwardMarkStore.getRange()` takes a single `positionId`. Options:
- (a) Add `getByPortfolioAndRange(tenantId, portfolioId, rangeStart, rangeEnd)` --
  requires a join or denormalization similar to Q-3.
- (b) First query L3 to get position IDs, then query S5b per position.
- (c) Since S5b is ephemeral and may be in Redis or an in-memory cache, bulk
  retrieval patterns differ from S5a. The port interface may need extension.

**Q-7: S6b trade interval cache for a portfolio-day (new).**
L4 forward day view and L1 open position require S6b data scoped by portfolio.
The current entity is indexed by `(trade_leg_id, interval_start)` and
`(tenant_id, interval_start)`. Options:
- (a) Use `tenantId` + interval range index, then filter by trade-leg IDs belonging
  to the portfolio (application-layer join).
- (b) Add a `portfolioId` denormalization to S6b for direct lookup.
- (c) Accept the two-step query: L3 gives trade-leg IDs; S6b is queried per leg.

**Q-8: Staleness detection for forward marks (new).**
L1 staleness indicator (AC-L1-08) requires comparing S5b `inputVersionSet` against
current versions of market data series. Options:
- (a) A dedicated service method that, given a portfolio's forward marks, checks
  each curve/volume/FX version against the latest known version.
- (b) A lightweight "is stale" flag maintained by the mark pipeline itself -- set
  when a CurveTick arrives but the mark has not yet been re-struck.

### Prerequisite: S5a bitemporality

Settlement cells (S5a) require bitemporality (`knownFrom`/`knownTo`) for audit trail
support -- the current implementation deletes-and-recreates on revaluation, which
loses the prior version. The dashboard does not itself need bitemporal as-of
querying for settlement cells, but the underlying data model change is a
prerequisite for:
- Reliable `computedAt` timestamps on settlement cells (the dashboard's "data as of"
  indicator).
- Audit trail compliance for back office reconciliation use cases.
- The knownTo = NULL filter pattern assumed by this spec (current knowledge only).

This is noted as a dependency. The implementation of S5a bitemporality is specified
in the technical spec, not this functional spec.

### Reference data dependencies

| Reference Data | Purpose |
|----------------|---------|
| Portfolio | Portfolio identifiers and names for L1 cards and all drill-down filters. Source: platform reference data. |
| DeliveryPoint | Delivery point identifiers and labels for L2 columns and L3 display. Source: platform reference data. |
| MarketCalendar | CET/CEST day boundaries, interval generation, DST transition detection. Used by all levels for date-to-UTC conversion. Source: `MarketCalendar` service. |
| Market Data Series (forward curves) | Curve version identifiers for staleness detection (AC-L1-08) and tooltip display (AC-L2-07). Source: S4 market data store. |
| Volume Series metadata | Forecast version identifiers for staleness detection and tooltip display. Source: volume series headers. |

---

## Edge Cases

### Cross-timezone considerations

- All dashboard queries operate in CET/CEST (Europe/Berlin) as the market-local
  timezone. The UI sends date selections in CET/CEST; the backend converts to UTC
  boundaries using `MarketCalendar`.
- If the platform ever supports non-EU markets (e.g., US PJM in Eastern Time),
  the dashboard must be parameterized by market timezone, not hardcoded to
  Europe/Berlin.

### Gate closure windows

Not directly applicable (read-only dashboard). However, the dashboard may display
settlement cells for intervals that have not yet passed gate closure and therefore
have no settlement data. These should be visually distinguishable from settled
intervals.

### Corrections and revaluations

- When settlement cells are recomputed (e.g., after a market data restatement),
  the dashboard reflects the latest values on the next query. With S5a
  bitemporality, prior versions are closed (knownTo set) but the dashboard
  always queries current knowledge (knownTo IS NULL).
- When forward marks are re-struck (e.g., after a curve tick), the dashboard
  reflects the new mark on the next query. S5b is ephemeral -- there is no
  history of prior mark values.
- The dashboard does not show historical versions of settlement cells. If
  bitemporal as-of viewing is needed (e.g., "what was the PnL as known on
  2026-08-10?"), that is a separate audit feature, not part of this dashboard.

### Cancellations

- Cancelled trades (status = "CANCELLED") are excluded from L3 display.
- Settlement cells for cancelled positions: with S5a bitemporality, cancellation
  closes the settlement cells (sets knownTo) rather than deleting them. The
  dashboard's knownTo IS NULL filter naturally excludes them.
- Forward marks for cancelled positions: `ForwardMarkStore.removeAll()` is called
  on cancellation (per existing implementation). The dashboard will show no
  forward data for cancelled positions.
- S6b entries for cancelled positions: should be removed on cancellation. The
  dashboard will show no forward volume for cancelled positions.

### Backdated trades

- A trade captured today with a delivery period in the past will generate
  settlement cells after materialization. The dashboard will show these cells
  once materialization completes.
- L3 will show the backdated trade's position ledger entry with the current
  `knownFrom` timestamp (reflecting when the system learned about it).
- Forward marks will be generated only for undelivered intervals of the backdated
  trade; delivered intervals will be handled by the settlement pipeline.

### Multi-currency

- Rollup cells (S7), settlement cells (S5a), and forward marks (S5b) carry a
  `currency` field.
- L1 portfolio cards: separate subtotals per currency for both realized PnL and
  unrealized MtM. No cross-currency netting.
- L2 rollup grid: each row has its own currency. Rows in different currencies are
  not summed.
- L4 interval detail (settled): all cells for a single position share the same
  currency (currency is a property of the trade). Cross-position views
  (portfolio-day) may show mixed currencies.
- L4 interval detail (forward): forward marks carry currency. Same rules apply.
- Cross-currency aggregation (e.g., converting GBP positions to EUR at a chosen
  FX rate for a single portfolio total) is a display-time enhancement, not part
  of the initial dashboard scope.

### Multi-tenant isolation

- Every query includes `tenantId` as a leading filter.
- The dashboard must not leak data across tenants under any circumstance.
- S5b forward marks, S6b trade interval cache, S5a settlement cells, S7 rollup
  cells, and S1 position ledger entries are all tenant-scoped.
- In the production host, Row-Level Security (RLS) policies enforce this at the
  database level. In the simulator, the hardcoded `"default"` tenant provides
  implicit isolation.

### Large portfolios

- A portfolio with hundreds of trades and millions of settlement cells may
  produce slow L4 day-view queries when scoped to the entire portfolio for a
  single day. Pagination or lazy loading may be necessary.
- L3 for a portfolio with hundreds of positions in a single month: pagination
  is recommended.
- Forward marks (S5b) for large portfolios: if marks are stored in Redis, bulk
  retrieval across many positions may require pipeline optimization.

### Empty date ranges

- If the selected date range produces no rollup cells (L2), no settlement
  cells (L4 settled), or no forward marks (L4 forward), the dashboard displays
  an appropriate empty state, not an error.

### Transition month (partially settled, partially forward)

- The current month is the most complex display scenario. Settlement cells exist
  for delivered days; forward marks exist for undelivered days. The boundary moves
  daily as intervals are delivered and settled.
- L2: The rollup cell for the transition month carries BOTH `settledValue`
  (accumulated from S5a) and `forwardMarkValue` (remaining from S5b). The sum
  gives the month's total estimated value.
- L4 month view: Each day row is independently classified as SETTLED, TODAY, or
  FORWARD. The grid shows a clear visual boundary.
- L4 day view for today: May show a mix of settled intervals (past gate closure)
  and forward intervals (future gate closure). The display must handle this
  intra-day boundary. Options: (a) show settled cells from S5a for past intervals
  and forward marks from S5b for future intervals on the same grid, or (b) show
  the day entirely from one source with a note that it will transition during the
  day. Decision deferred to UI specification.

### Forward curve not yet available for a delivery period

- Long-dated PPAs may have delivery periods beyond the liquid forward curve
  horizon (e.g., delivery in 2035 but the forward curve only extends to 2030).
- In this case, S5b forward marks will not be struck for those intervals
  (ForwardMarkJob cannot evaluate the price expression without curve data).
- S6b volume data may still exist (forecast volumes are independent of curve
  availability).
- L4 forward day view for such periods: show volume data from S6b, mark values
  as null, with a message "Forward curve not available for this delivery period."
- L2 rollup cells for such periods: `forwardMarkValue` = null or zero; `netMw`
  and `netMwh` may still be populated from S6b if the rollup pipeline supports it.

### Volume forecast not yet published for a future period

- If a trade's asset has no forecast volume series for a future delivery period,
  S6b will have no entries, and S5b marks cannot be computed (no volume input).
- L4 forward view: empty with "Volume forecast not published" message.
- L1: the position's contribution to Unrealized MtM is zero until the forecast
  arrives. This may cause the total portfolio value to understate true exposure.
  The staleness indicator should flag positions with missing forecast data.

### Stale forward marks (curve updated but marks not re-struck)

- After a CurveTick event, there is a latency window before ForwardMarkJob
  re-strikes affected positions. During this window, the displayed MtM is stale.
- The staleness indicator (AC-L1-08) addresses this at L1. At L4, each forward
  mark's `inputVersionSet` can be compared against current curve versions.
- The dashboard does NOT attempt to compute marks on the fly. It reads
  pre-materialized marks. Staleness is communicated, not remedied, by the
  dashboard.

### Position amended after forward mark struck -- mark invalidation

- When a position is amended (quantity change, price expression change, volume
  reference change), existing forward marks for that position become invalid.
- The amendment event triggers ForwardMarkJob to re-strike marks for the
  affected position. Until re-striking completes, old marks remain in S5b.
- The dashboard should detect this via version mismatch: the mark's
  `inputVersionSet` references an older expression or volume version than the
  current position ledger entry.
- This is a specific case of the general staleness pattern (AC-L1-08).

---

## Open Questions

**OQ-1: DAILY rollup materialization.**
S7 currently materializes WEEKLY, MONTHLY, YEARLY. The L4 month view needs DAILY
granularity. Per the upward-only hierarchy in AC-L2-06, DAILY would be a fourth
independent aggregation from S5a 15-min cells — not derived from WEEKLY or
MONTHLY. Storage impact: for a tenant with 300 deals, 12 months of DAILY rollups
adds approximately 300 x 12 x 31 x 2 (peak/off-peak) = ~223,000 rollup rows —
modest. **Recommendation:** add DAILY to the materialization pipeline alongside
WEEKLY/MONTHLY/YEARLY, giving the full hierarchy (DAILY, WEEKLY, MONTHLY, YEARLY)
all pre-materialized from S5a. Sub-daily views (15/30/60-min at L4 day view)
query S5a cells directly, not S7. Decision required from solutions-architect.

**OQ-2: 30-minute and 60-minute aggregation location.**
Should sub-daily aggregation (15min to 30min/60min) happen in the backend (new
query service method) or the frontend? This now applies to both S5a settlement
cells and S5b/S6b forward data. Arguments for backend: domain logic (FR-035 TWA
rules) stays server-side; consistent across clients. Arguments for frontend:
max 100 cells per day is trivial to aggregate; avoids new backend endpoints.
Recommendation: backend, to keep FR-035 enforcement in the domain layer.
Decision required.

**OQ-3: L1 portfolio card query strategy.**
Should L1 cards be served by a dedicated "portfolio summary" query that aggregates
rollup cells across all delivery points, or should the UI fetch L2 data and
aggregate client-side? The latter is simpler but may transfer excessive data for
portfolios with many delivery points and long date ranges. With the addition of
forward mark data, the query must now return both settled and forward components.
A dedicated query is more efficient but adds a new port method. Decision required.

**OQ-4: L4 portfolio-day netting.**
When L4 day view is scoped to a portfolio (not a single position), should data be:
(a) displayed individually per position (one row per position per interval), or
(b) netted across positions per interval (one row per interval)?
This applies to both settled day view (S5a) and forward day view (S5b/S6b).
Option (a) provides full transparency but may produce a large grid. Option (b)
provides a cleaner summary but loses per-trade attribution. A toggle between the
two modes may be ideal. Decision required from UX.

**OQ-5: Peak/off-peak handling in L2.**
Rollup cells are materialized per peak/off-peak split (FR-090 `isPeak` flag).
Should L2 display:
(a) separate rows for peak and off-peak,
(b) a combined row with peak and off-peak as sub-columns,
(c) a toggle to switch between peak/off-peak/combined views?
Decision required from UX.

**OQ-6: Forward mark granularity for risk grid (new).**
Should forward marks be struck at DAILY granularity (aggregated from 15-min) for
the risk grid, or only MONTHLY? The current S5b model stores marks per 15-min
interval per position, which is very granular for a risk overview but correct for
the interval-level L4 view. For L2 and L1, the rollup pipeline aggregates S5b
into `forwardMarkValue` on the rollup cell. If risk users need daily-grain forward
marks in L4 month view, the 15-min marks must be aggregated (on the fly or via
DAILY rollups -- see OQ-1). Decision required from product/risk.

**OQ-7: Real-time refresh vs. poll-based.**
Should the dashboard auto-refresh when new settlement or forward mark data is
materialized (via WebSocket push keyed by `versionHash` changes), or should it
rely on manual refresh / periodic polling? For settlement data, batch cadence is
acceptable. For forward marks, traders may want near-real-time MtM updates when
curves move. Decision required from product/UX.

**OQ-8: Pagination strategy for L3 and L4.**
What are the expected maximum row counts for:
- L3: positions per portfolio-month (estimate: 10--200)
- L4 settled day view at portfolio scope (estimate: 100--20,000 cells if many positions)
- L4 forward day view at portfolio scope (similar to settled)
- L4 month view (31 rows -- no pagination needed)

Are these small enough for single-fetch, or do L3 and L4 need cursor-based
pagination? Decision required based on tenant size profiles.

**OQ-9: Drill-down context passing.**
When the user drills from L2 to L3, the context includes (portfolioId,
periodStart, periodEnd). When drilling from L3 to L4, the context includes
(positionId or portfolioId, deliveryDay). The drill-down must now also carry
whether the target is a settled or forward period (to determine which L4 variant
to render). Should the drill-down be implemented as:
(a) URL-based navigation with query parameters (deep-linkable),
(b) in-memory state within a single-page view (not deep-linkable), or
(c) both (URL reflects state for bookmarking, but navigation is SPA-style)?
Decision required from UI architect.

**OQ-10: Settlement cell `portfolioId` denormalization.**
Query Q-3 (settlement cells for a portfolio-day) currently requires a join from
S5a to S1 to resolve `portfolioId`. Should `portfolioId` be denormalized onto
the settlement cell for query efficiency? This trades write-time cost
(maintaining the denormalization on portfolio reassignment) for read-time
efficiency. The same question applies to S5b forward marks and S6b trade interval
cache entries. Decision required from solutions-architect.

**OQ-11: Forward mark refresh strategy on curve update (new).**
When a forward curve updates (CurveTick event), forward marks are re-struck via
ForwardMarkJob. For a large portfolio with hundreds of positions across years of
forward delivery, re-striking all marks can be computationally expensive. Should
the mark refresh be:
(a) Real-time (triggered by each CurveTick, processed via dependency index for
    targeted positions only -- the current design per FR-103),
(b) Batch (accumulated curve updates are processed in a scheduled batch cycle),
(c) Hybrid (near-term months are real-time, far-dated months are batch)?
The answer affects the freshness promise the dashboard can make. Decision required.

**OQ-12: Should S6b become bitemporal for forward position audit?**
S6b is currently a rebuildable cache with no history (D-12). For forward position
audit purposes ("what was our open position as known on date X?"), would
bitemporality on S6b be valuable?

Regulatory analysis:
- **REMIT Art. 8:** reports contracts (from S1), not internal position views.
- **EMIR Art. 9:** reports contracts + daily MtM valuations (S1 + S4 market data).
- **MiFID II RTS 25:** aggregate net position per contract/maturity, derived from S1.
- None of these regulatory submissions use S6b. All derive from S1 (bitemporal)
  + S4 (market data) + S3 (versioned volume series).

Reconstruction capability: S3 volume series are versioned (append-only with
`versionId` + `transactionTime`; intervals carry `version` + `supersedesId`
chain). Given any knowledge date K: query S1 as-of K → get positions; query S3
at version valid at K → get forecast volumes; multiply → forward position as-of
K. This is exactly what S6b materialization does, just on-demand.

**Recommendation: No bitemporality on S6b.** Rationale:
1. Regulatory bodies never receive S6b — they receive S1 + S4 derived reports
2. Historical forward position is reconstructable from S1 (bitemporal) + S3
   (versioned) on demand
3. "As-of forward exposure" is a rare dashboard query — most users view current
   state. For the rare case, on-demand reconstruction from S1+S3 is acceptable
4. Bitemporality on S6b would massively increase storage (D-12 explicitly marks
   S6b as "optional, rebuildable") for marginal benefit

If product/risk later requires frequent as-of forward position queries with
sub-second latency, consider a periodic snapshot approach (daily EOD snapshot
of S6b) rather than full bitemporality. Decision: **closed — no bitemporality
on S6b**, but flagging the EOD snapshot option as a future enhancement.

**OQ-13: Indicative vs official mark labeling (new).**
The dashboard displays S5b forward marks, which are ephemeral and indicative. The
official EMIR mark is S5c (EOD struck mark), which the dashboard does NOT display.
What labeling convention should the UI use to make this distinction clear? E.g.,
"Current MtM (indicative)" vs. "EOD Mark (official)". Decision required from
compliance/product.

**OQ-14: Open position calculation scope (new).**
AC-L1-07 defines "open position" as net MW for the next delivery month. Should the
dashboard also show:
(a) Open position per forward month (a term structure of exposure)?
(b) Open position across all forward months (total forward MW)?
(c) Open position by delivery point within a portfolio?
The answer determines additional S6b query patterns. Decision required from
product/risk.
