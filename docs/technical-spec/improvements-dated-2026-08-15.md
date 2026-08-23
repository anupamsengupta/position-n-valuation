# Technical Specification — Improvements 2026-08-15

## Overview

Six enhancements implemented on branch `Spec-2-dashboard-impl1`, covering L1 portfolio summary forward computation, L1 transition-month fix, sidebar dynamic portfolio list, L3 delivery date columns, controller request logging, and a React infinite-loop fix in the dashboard state management:

1. **L1 Portfolio Summary: On-the-Fly Forward Computation** — `portfolioSummaries()` now computes forward MtM/MW/MWh on-the-fly via `ForwardMarkService` + `TradeIntervalCache`, fixing zero-value forward cards
2. **L1 Portfolio Summary: Transition-Month Settled Data Fix** — Removed incorrect `isSettled` filter that dropped rollup cells for the current month, restoring realized PnL/MW/MWh on portfolio cards
3. **Sidebar: Dynamic Portfolio List from API** — Replaced hardcoded `KNOWN_PORTFOLIOS` with `usePortfolios()` hook backed by `/api/dashboard/portfolios`
4. **L3 Position Contributions: Delivery Date Columns** — Added `deliveryStart` and `deliveryEnd` columns to the position ledger grid
5. **Controller Request Logging** — Added `log.info` on entry and exit for all 9 REST controllers in `pv-app`
6. **React Infinite Loop Fix** — Resolved `Maximum update depth exceeded` caused by competing `useEffect` hooks and non-idempotent Zustand `clearAll`

All changes are backward-compatible.

---

## Enhancement 1: L1 Portfolio Summary — On-the-Fly Forward Computation

### 1.1 Problem

`DefaultDashboardQueryService.portfolioSummaries()` read exclusively from `rollupRepo.findByPortfolio()`. Rollup cells are materialized from settlement cells (S5a) on `SettlementComputed` events. Two gaps caused forward values to always be zero:

1. **Forward periods have no rollup cells** — delivery months in the future never trigger settlement, so no rollup cells exist for them.
2. **`forwardMarkValue` is always `BigDecimal.ZERO`** in materialized rollup cells — the planned `ForwardMarkJob` that would populate this field doesn't exist yet.

L3 `positionContributions()` already solved this same problem via on-the-fly computation with `ForwardMarkService.computeMonthlyMark()`. L1 was the only level still relying solely on rollup cells for forward data.

### 1.2 Changes

**File:** `pv-domain/.../service/DefaultDashboardQueryService.java`

The method now follows a three-step hybrid approach:

```
Step 1: rollupRepo.findByPortfolio() → aggregate ALL rollup cells into settled accumulators (by currency)
Step 2: ledgerRepo.findByPortfolioAndDeliveryRange() → find forward positions
        → tradeIntervalCache.getForTradeLegIds() bulk fetch for forward volume (MW/MWh)
        → forwardMarkService.computeMonthlyMark() per position for unrealized MtM
        → aggregate into forward accumulators (by currency)
Step 3: Merge settled + forward accumulators per currency → PortfolioSummary records
```

Key design decisions:

- **Bulk S6b fetch** via `tradeIntervalCache.getForTradeLegIds()` — same pattern as L3 (lines 267-276), avoids N+1
- **`ForwardMarkService.computeMonthlyMark()`** per forward position — same pattern as L3 (lines 294-303), with `try/catch` per position to avoid one failure breaking the entire summary
- **Forward range starts at `max(now, rangeStart)`** — only fetches intervals after the current moment, preventing overlap with settled data
- **Settled data still comes from rollup cells** — efficient for historical data, no change to S7 materialization

Two private mutable accumulator classes replace inline variable soup:

```java
private static final class SettledAccumulator {
    BigDecimal realizedPnl = BigDecimal.ZERO;
    BigDecimal mwWeightedSum = BigDecimal.ZERO;
    long totalMinutes = 0L;
    BigDecimal netMwh = BigDecimal.ZERO;
}

private static final class ForwardAccumulator {
    BigDecimal unrealizedMtm = BigDecimal.ZERO;
    BigDecimal mwWeightedSum = BigDecimal.ZERO;
    long totalMinutes = 0L;
    BigDecimal netMwh = BigDecimal.ZERO;
}
```

### 1.3 No Interface Changes

All dependencies (`ForwardMarkService`, `TradeIntervalCache`, `PositionLedgerRepository`) were already injected into `DefaultDashboardQueryService`. The `PortfolioSummary` record already had all needed fields. No new ports or wiring changes required.

---

## Enhancement 2: L1 Transition-Month Settled Data Fix

### 2.1 Problem

The initial implementation of Enhancement 1 introduced a regression: rollup cells for the current (transition) month were filtered out by an `isSettled` check:

```java
boolean isSettled = cell.periodEnd() != null && !cell.periodEnd().isAfter(now);
if (!isSettled) {
    continue; // BUG: drops transition-month rollup cell
}
```

August 2026's MONTHLY rollup cell has `periodEnd = 2026-09-01T00:00Z`, which is after `now` (Aug 15). The cell was classified as "not settled" and skipped entirely, even though its `pnl`/`settledValue`/`netMw`/`netMwh` fields contain valid data for the delivered intervals (Aug 1-15).

The L2 rollup grid showed correct values because `rollupGrid()` returns cells directly without filtering. L1 was the only path that filtered.

### 2.2 Fix

Removed the `isSettled` filter. ALL rollup cells now accumulate into settled accumulators. This is correct because:

- Rollup cells only contain **materialized settlement data** — settlement events only fire for delivered (past) intervals
- A transition month's rollup cell has numeric values that reflect only the settled portion
- Forward data is computed separately via `ForwardMarkService` in Step 2 — no double-counting because rollup cells and forward intervals cover disjoint time ranges

---

## Enhancement 3: Sidebar — Dynamic Portfolio List from API

### 3.1 Problem

The left-hand sidebar used a hardcoded `KNOWN_PORTFOLIOS` array (`WIND_DE`, `SOLAR_DE`, `GAS_NL`) that did not reflect actual portfolios in the database. Users saw navigation links for portfolios that don't exist and couldn't see portfolios that do exist.

### 3.2 Changes

**File:** `pv-ui/src/components/layout/Sidebar.tsx`

- Replaced static `KNOWN_PORTFOLIOS` import with `usePortfolios()` hook
- The hook calls `GET /api/dashboard/portfolios?tenantId=...` which queries `SELECT DISTINCT portfolioId FROM position_ledger_entry WHERE knownTo IS NULL AND status = 'ACTIVE'`
- Shows loading state while fetching, "No portfolios found" when empty
- Each portfolio renders as a `<Link>` to `/dashboard/$portfolioId` with active-state highlighting

The `DashboardFilterBar` dropdown was already wired to `usePortfolios()` with `KNOWN_PORTFOLIOS` as fallback — unchanged.

---

## Enhancement 4: L3 Position Contributions — Delivery Date Columns

### 4.1 Problem

The L3 position ledger grid showed trade/leg IDs, status, and financial values but not the delivery period dates. Users couldn't see when each position's delivery starts and ends without drilling into L4.

### 4.2 Changes

**File:** `pv-ui/src/components/dashboard/PositionLedger.tsx`

Added two columns before the Status column:

| Column | Accessor | Format | Width |
|--------|----------|--------|-------|
| Start | `deliveryStart` | `dd MMM yyyy` (en-GB) | 100px |
| End | `deliveryEnd` | `dd MMM yyyy` (en-GB) | 100px |

The `PositionContributionDto` already included `deliveryStart` and `deliveryEnd` fields from the backend — only the UI column definitions were missing.

---

## Enhancement 5: Controller Request Logging

### 5.1 Problem

No request-level logging in any controller made it difficult to trace API calls, debug parameter mismatches between UI and backend, and correlate request/response in the Spring Boot log output.

### 5.2 Changes

Added `log.info` on entry and exit for every endpoint method across all 9 controllers:

| Controller | Endpoints | Logged Fields |
|---|---|---|
| `DashboardController` | 7 (L0-L4) | tenantId, portfolioId, ranges, granularity, result counts |
| `TradeController` | 3 (capture/amend/cancel) | tradeId, tradeLegId, tenantId, entry counts |
| `EventTriggerController` | 2 (market-data/volume) | tenantId, series/seriesKey, dataType/layer |
| `HealthController` | 2 (health/cache) | status, db/redis connectivity |
| `MarketDataController` | 8 (CRUD fixings/curves/indices/fx) | tenantId, series, date params, found/saved |
| `PositionController` | 4 (current/as-of/range/summary) | tenantId, tradeId, ranges, counts |
| `RollupController` | 2 (query/materialize) | tenantId, portfolio, range, granularity, cell count |
| `SettlementController` | 1 (findByPosition) | tenantId, positionId, range, cell count |
| `VolumeSeriesController` | 2 (findByTenant/findById) | tenantId/id, series count |

`DashboardSseController` already had logging — unchanged.

Entry log format: `{HTTP_METHOD} {path} param1={} param2={} ...`
Exit log format: `{HTTP_METHOD} {path} => {result_count} {entity_type}`

---

## Enhancement 6: React Infinite Loop Fix

### 6.1 Problem

After Enhancement 3 changed the sidebar to use dynamic portfolios from the API, the app crashed with:

```
Error: Maximum update depth exceeded.
```

Stack trace: `setPortfolioId → clearDrillDown → clearAll → setState → re-render → setPortfolioId → ...`

### 6.2 Root Cause

Two competing `useEffect` hooks created a feedback loop:

1. **`DashboardFilterBar.useEffect`**: Detects the store's default `portfolioId` (`'WIND_DE'`) isn't in the server portfolio list → calls `setPortfolioId(firstRealPortfolio)` + `navigate()`
2. **`DashboardPage.useEffect`**: Detects route `portfolioId` !== store `portfolioId` → calls `setPortfolioId(routePortfolioId)`

Since `navigate()` is async, before the route updates, `clearAll()` (called internally by `setPortfolioId` via `clearDrillDown`) triggers a re-render of `DashboardPage`. The component sees the OLD route portfolioId (still `'WIND_DE'`), resets the store back, which triggers `DashboardFilterBar` again.

Compounding factor: `clearAll()` created a new `selectedRangeStarts: []` array reference on every call, even when the state was already cleared. This caused Zustand subscribers to re-render even when no state actually changed.

### 6.3 Fixes

**File:** `pv-ui/src/hooks/useDashboardFilters.ts`

1. `setPortfolioId` now short-circuits when the value hasn't changed:
```typescript
setPortfolioId: (id) => {
    if (id === get().portfolioId) return;  // prevent ping-pong
    clearDrillDown();
    set({ portfolioId: id });
},
```

2. Default `portfolioId` changed from `'WIND_DE'` (hardcoded, doesn't exist in real data) to `''` (empty, triggers auto-select on first server response).

**File:** `pv-ui/src/hooks/useDashboardSelection.ts`

3. `clearAll` made idempotent — returns the same state reference when already cleared:
```typescript
clearAll: () =>
    set((state) => {
        if (state.selectedPeriod === null && state.anchorPeriodStart === null &&
            state.selectedRangeStarts.length === 0 && state.selectedPositionId === null &&
            state.selectedDay === null && state.selectedDayStatus === null) {
            return state;  // no new references, no re-render
        }
        return { selectedPeriod: null, anchorPeriodStart: null, selectedRangeStarts: [],
                 selectedPositionId: null, selectedDay: null, selectedDayStatus: null };
    }),
```

---

## Files Modified

### pv-domain (library-scope)
- `pv-domain/src/main/java/com/power/posval/domain/service/DefaultDashboardQueryService.java` — Enhancements 1, 2

### pv-app (simulator-scope)
- `pv-app/src/main/java/com/power/posval/app/controller/DashboardController.java` — Enhancement 5
- `pv-app/src/main/java/com/power/posval/app/controller/TradeController.java` — Enhancement 5
- `pv-app/src/main/java/com/power/posval/app/controller/EventTriggerController.java` — Enhancement 5
- `pv-app/src/main/java/com/power/posval/app/controller/HealthController.java` — Enhancement 5
- `pv-app/src/main/java/com/power/posval/app/controller/MarketDataController.java` — Enhancement 5
- `pv-app/src/main/java/com/power/posval/app/controller/PositionController.java` — Enhancement 5
- `pv-app/src/main/java/com/power/posval/app/controller/RollupController.java` — Enhancement 5
- `pv-app/src/main/java/com/power/posval/app/controller/SettlementController.java` — Enhancement 5
- `pv-app/src/main/java/com/power/posval/app/controller/VolumeSeriesController.java` — Enhancement 5

### pv-ui (frontend)
- `pv-ui/src/components/layout/Sidebar.tsx` — Enhancement 3
- `pv-ui/src/components/dashboard/PositionLedger.tsx` — Enhancement 4
- `pv-ui/src/hooks/useDashboardFilters.ts` — Enhancement 6
- `pv-ui/src/hooks/useDashboardSelection.ts` — Enhancement 6

### docs
- `docs/technical-spec/improvements-dated-2026-08-15.md` — This document
