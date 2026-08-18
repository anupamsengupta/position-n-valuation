# UI Technical Specification -- Dashboard Multi-Select Enhancements v1.0

## S1 -- Metadata

| Field             | Value                                                                                   |
|-------------------|-----------------------------------------------------------------------------------------|
| Author            | ui-solution-architect                                                                   |
| Status            | DRAFT                                                                                   |
| Version           | 1.0                                                                                     |
| Date              | 2026-08-17                                                                              |
| Depends On        | trade-leg-rollup-v1.0 (S15 -- server-side multi-select API), position-pnl-dashboard-v1.0 (L3/L4 component design) |
| Layer             | UI (`pv-ui`)                                                                            |
| Components Touched| `PositionLedger`, `MonthViewGrid`, `IntervalDetailPanel`, `DashboardPage`, `DashboardFilterBar`, `PortfolioCardStrip`, `PortfolioCard`, `useDashboardSelection`, `useDashboardQueries`, `dashboard.ts`, `queryKeys.ts` |

---

## S2 -- Scope

### 2.1 In Scope

1. **Feature 1: Multi-select trade legs in L3 PositionLedger** -- checkbox-based multi-row selection with Ctrl+Click toggle, Shift+Click range, Select All header. Selected position IDs flow into L4 queries as repeatable `positionId` params. L4 views show netted aggregates across the selected subset.

2. **Feature 2: Contiguous day range selection in L4 MonthViewGrid** -- Shift+Click to select a contiguous range of days. Sub-daily grids load the full range in a single API call. Day boundaries are visually separated in the interval grid.

3. **Feature 3: Portfolio Card Strip replacing dropdown selector** -- horizontal scrollable row of portfolio summary cards at the top of the dashboard. All portfolio summaries visible at once, sorted by absolute total value descending. Click a card to select that portfolio; replaces the `<select>` dropdown in `DashboardFilterBar`. The rest of the dashboard (L1 summary, L2 rollup, L3/L4 drill-down) loads for the selected portfolio.

4. **Selection state management** -- extension of the existing `useDashboardSelection` Zustand store to hold multi-position and multi-day state.

5. **API function and React Query hook changes** to support `List<UUID>` position IDs and multi-day ranges.

6. **Accessibility** -- WCAG 2.2 AA compliance for all new interactive patterns.

### 2.2 Out of Scope

- Server-side implementation (covered by trade-leg-rollup-v1.0 S15).
- AG Grid migration -- the current TanStack Table implementation is sufficient for ~200 position rows and ~31 day rows.
- Drag-select (lasso) in grids.
- Persistent selection state across page reloads (selections are transient).
- Position filtering/search within the L3 grid (future enhancement).
- Multi-portfolio selection (selecting more than one card at a time). Each card click is a single-select that replaces the previous portfolio context.
- Portfolio card reordering or drag-and-drop rearrangement. Sort order is always by absolute total value descending.
- Collapsing or hiding the card strip. It is always visible at the top of the dashboard.

---

## S3 -- Information Architecture

No new routes. All three features operate within the existing `/dashboard/:portfolioId` route. Feature 3 changes the portfolio selection mechanism from a dropdown to a card strip, but the route parameter `portfolioId` still drives the selected portfolio. Clicking a card navigates to `/dashboard/:newPortfolioId`. No new URL search params for selection state (selection is ephemeral UI state, not deep-linkable).

---

## S4 -- Screens & Layouts

### 4.1 L3 PositionLedger -- Multi-Select Enhancement

**Current behavior:** Single-click a row to select one position. The selected position ID is passed to L4 `IntervalDetailPanel`. One position at a time.

**New behavior:**

```
+------------------------------------------------------------------+
| Position Contributions   Sep 2026   [TRANSITION]        [Close]  |
+------------------------------------------------------------------+
| [x] Select All (3 of 20 selected)       [View Netted ->]        |
+--+--------+-----+-------+------+-------+-------+--------+-------+
|  | Trade  | Leg | Start | End  | Status| S.MW  | S.MWh  | PnL   |
+--+--------+-----+-------+------+-------+-------+--------+-------+
|[x]| T-7788| L1  | 01Sep | 30Sep| PARTIAL| 12.50 | 9,120  | 1,240 |
|[ ]| T-7790| L1  | 01Sep | 30Sep| SETTLED| -5.00 | -3,600 | -820  |
|[x]| T-7792| L1  | 01Sep | 30Sep| FORWARD|  0.00 |     0  |     0 |
|[x]| T-7794| L2  | 01Sep | 30Sep| PARTIAL|  8.30 | 6,048  |   450 |
+--+--------+-----+-------+------+-------+-------+--------+-------+
```

**UI elements added:**

1. **Checkbox column** -- first column, 28px width. Each row has a checkbox (`<input type="checkbox">`). Header has a tri-state checkbox (unchecked / indeterminate / checked).

2. **Selection summary bar** -- appears above the grid when 2+ positions are selected. Shows: "Showing netted view for N positions" with a "Clear selection" button.

3. **Behavioral change to row click:** Plain click on the row body (not the checkbox) still sets a single `selectedPositionId` for the L4 detail view (backward compatible). Clicking the checkbox toggles that row's inclusion in the multi-select set. This is the standard "selection vs activation" pattern (ARIA grid with selectable rows).

**Keyboard shortcuts:**

| Key | Action |
|-----|--------|
| Space | Toggle checkbox on focused row |
| Ctrl+A | Select all / deselect all (when grid is focused) |
| Shift+Space | Select contiguous range from anchor to focused row |
| ArrowUp/ArrowDown | Move focus (existing) |
| Enter | Activate row (navigate to L4 single-position detail, existing) |
| Escape | Clear multi-selection if active; otherwise close panel (existing) |

**Loading state:** Skeleton table unchanged; checkbox column included in skeleton.

**Empty state:** Unchanged.

**Error state:** Unchanged.

### 4.2 L4 IntervalDetailPanel -- Netted View Indicator

**Current behavior:** Shows "Position: abcd1234..." in the header when a single position is selected.

**New behavior when multi-select is active (2+ positions in the selection set):**

```
+------------------------------------------------------------------+
| Interval Detail                                                   |
| Showing netted view for 3 positions                    [Close]   |
+------------------------------------------------------------------+
```

- The header replaces the single position ID with a netted view indicator.
- A chip/badge list of the selected trade IDs can optionally be shown (collapsed behind a "Show details" toggle if more than 3).
- All data in the daily summary and sub-daily grids represents the netted aggregate.

### 4.3 Portfolio Card Strip -- Replacing Dropdown Selector (Feature 3)

**Current behavior:** A `<select>` dropdown in `DashboardFilterBar` lets the user pick one portfolio at a time. The user cannot see summary metrics for other portfolios without switching the dropdown.

**New behavior:** A horizontal scrollable strip of portfolio cards replaces the dropdown. All portfolios are visible at a glance with their key metrics. Clicking a card selects that portfolio and loads the rest of the dashboard for it.

```
+------------------------------------------------------------------+
| Position & PnL Dashboard                           [Live]        |
+------------------------------------------------------------------+
| Portfolio Cards                                   scroll -->     |
| +------------------+ +------------------+ +------------------+   |
| | WIND_DE     [*]  | | SOLAR_DE         | | GAS_NL           |   |
| | Total: 142,350   | | Total:  87,200   | | Total:  23,100   |   |
| |                  | |                  | |                  |   |
| | Realized:  98,200| | Realized: 62,100 | | Realized: 18,400 |   |
| | Unreal:    44,150| | Unreal:   25,100 | | Unreal:    4,700 |   |
| |                  | |                  | |                  |   |
| | MW:  12.5 / 8.3  | | MW:   5.0 / 3.2  | | MW:   2.1 / 1.0  |   |
| | MWh: 9120 / 6048 | | MWh:  3600 / 2304| | MWh:  1512 / 720 |   |
| | EUR              | | EUR              | | EUR              |   |
| +------------------+ +------------------+ +------------------+   |
+------------------------------------------------------------------+
| [Granularity: Monthly v]  [<  2026  >]  [This Mth|Qtr|Yr|Next]  |
+------------------------------------------------------------------+
| L1: Portfolio Summary for WIND_DE (existing component)           |
| ...                                                              |
```

**Card layout (each card ~220px wide):**

```
+--------------------+
| Portfolio Name  [*] |   <-- [*] = selected indicator (filled dot)
| Total: 142,350 EUR  |   <-- |realizedPnl + unrealizedMtm|, bold
|                      |
| Realized:   98,200   |   <-- MONETARY precision (4 dp, display 0-2)
| Unrealized: 44,150   |   <-- MONETARY precision
|                      |
| S.MW / F.MW          |   <-- settled / forward net MW
| S.MWh / F.MWh        |   <-- settled / forward net MWh
| EUR                  |   <-- currency badge
+--------------------+
```

**UI elements:**

1. **Card strip container** -- `overflow-x: auto` with `scroll-snap-type: x mandatory` for clean alignment. Sits between the dashboard header and the filter toolbar. Cards do not wrap to a vertical stack on narrow viewports; they remain horizontal and scroll.

2. **Individual cards** -- fixed width `w-[220px]`, minimum height to fit all fields. Border and background change on selection: selected card gets `border-interactive-focus` (2px) and a subtle `bg-interactive-row-selected` background. Unselected cards have `border-border-default`.

3. **Sort order** -- cards are sorted by `Math.abs(realizedPnl + unrealizedMtm)` descending. The portfolio with the highest absolute total value is always first (leftmost, visible without scrolling).

4. **Auto-selection on load** -- when the page loads and no portfolio is in the route param (or the route param is invalid), the first card (highest value) is auto-selected.

5. **Skeleton state** -- while portfolio summaries are loading, show 3 skeleton cards at 220px width with pulsing placeholder bars for each metric row.

**Keyboard shortcuts:**

| Key | Action |
|-----|--------|
| ArrowLeft | Move focus to previous card |
| ArrowRight | Move focus to next card |
| Home | Move focus to first card |
| End | Move focus to last card |
| Enter / Space | Select the focused card (navigate to that portfolio) |
| Tab | Move focus out of the card strip to the next toolbar element |

**Loading state:** 3 skeleton cards at `w-[220px]` with animated placeholder bars matching the card layout.

**Empty state:** If `usePortfolios` returns an empty array, show a single card-shaped empty state: "No portfolios found for this tenant."

**Error state:** If fetching portfolio summaries fails for a specific portfolio, that card shows the portfolio name with an error icon and "Failed to load" text. Other cards continue to render normally.

### 4.4 L4 MonthViewGrid -- Contiguous Day Range Selection

**Current behavior:** Click a day row to select that day. Selected day gets `bg-interactive-row-selected`. Sub-daily grid loads intervals for that one day.

**New behavior:**

```
+------------------------------------------------------------------+
| Daily Summary                                                     |
+------------------------------------------------------------------+
| Date      | Status  | Intv | S.MW  | S.MWh | PnL    | Fwd MW ...
+-----------|---------|------|-------|-------|--------|-------
| 01 Sep    | SETTLED | 96   | 12.50 | 300.0 | 1,240  |  ...
| 02 Sep    | SETTLED | 96   | 12.50 | 300.0 | 1,180  |  ...   <- range start (selected)
| 03 Sep    | SETTLED | 96   | 12.50 | 300.0 | 1,310  |  ...   <- in range (selected)
| 04 Sep    | SETTLED | 96   | 12.50 | 300.0 | 1,050  |  ...   <- range end (Shift+clicked)
| 05 Sep    | TODAY   | 72   |  9.20 | 220.8 |   820  |  ...
```

- Click sets a single day (existing behavior, also sets the anchor).
- Shift+Click sets a contiguous range from the anchor to the clicked day.
- All rows in the range get `bg-interactive-row-selected`.
- Range header updates: "Sep 2 -- Sep 4, 2026" instead of "Sep 2, 2026".
- Shift+ArrowDown / Shift+ArrowUp extends the range by one row.
- Maximum 31 days enforced: if Shift+Click would exceed 31 days, the range is clamped to 31 days from the anchor, and a toast/tooltip informs the user.

**Sub-daily grid with multi-day range:**

When a day range is selected, the sub-daily grid (SettledDayGrid / ForwardDayGrid / horizontal variants) receives `dayStart` = first day's start, `dayEnd` = last day's end. The grid renders all intervals across the range. Day boundaries are marked with a visual separator.

```
+-----------+-------+-------+-------+--------+--------+-------+
| Time      | MW    | MWh   | Price | Amount | Market | PnL   |
+-----------+-------+-------+-------+--------+--------+-------+
| 02 Sep 2026 ------------------------------------------------|  <- Day header
| 00:00 CET | 12.50 | 3.125 | 45.20 | 141.25 | 140.00 | 1.25 |
| 00:15 CET | 12.50 | 3.125 | 45.18 | 141.19 | 140.00 | 1.19 |
| ...       |       |       |       |        |        |       |
| 23:45 CET | 12.50 | 3.125 | 44.90 | 140.31 | 140.00 | 0.31 |
+-----------+-------+-------+-------+--------+--------+-------+  <- Day boundary
| 03 Sep 2026 ------------------------------------------------|  <- Day header
| 00:00 CET | 12.50 | 3.125 | 45.50 | 142.19 | 140.00 | 2.19 |
| ...       |       |       |       |        |        |       |
```

Day boundary rendering: a full-width row with the date label, styled with `bg-bg-secondary font-semibold text-xs` and a 2px top border in `border-border-grid`. This row is not a data row and is not focusable via arrow keys (skip during keyboard navigation).

---

## S5 -- Components

### 5.1 PositionLedger (modified)

**File:** `pv-ui/src/components/dashboard/PositionLedger.tsx`

**Props changes:**

```typescript
export interface PositionLedgerProps {
  data: PositionContributionDto[] | undefined;
  isLoading: boolean;
  periodLabel: string;
  periodStatus: PeriodStatus;
  // REMOVED: selectedPositionId: string | null;
  // NEW: multi-select state
  selectedPositionIds: ReadonlySet<string>;
  activatedPositionId: string | null; // the single "activated" row for L4 detail
  onRowActivate: (row: PositionContributionDto) => void; // Enter/click on row body
  onSelectionChange: (selectedIds: ReadonlySet<string>) => void; // checkbox interactions
  onClose: () => void;
}
```

**Rationale for separating "activation" from "selection":**

This follows the ARIA grid pattern where `aria-selected` represents checkbox-style multi-selection, while "activation" (Enter key or row body click) represents navigating to a detail view. These are distinct user intents. A trader may select 5 positions for a netted view, then activate (Enter) one specific position to drill into its individual intervals. The two concepts must not be conflated.

**New column: Checkbox (column index 0):**

```typescript
columnHelper.display({
  id: 'select',
  header: ({ table }) => (
    <SelectAllCheckbox
      checked={allSelected}
      indeterminate={someSelected && !allSelected}
      onChange={handleSelectAll}
      totalCount={data.length}
      selectedCount={selectedPositionIds.size}
    />
  ),
  cell: ({ row }) => (
    <RowCheckbox
      checked={selectedPositionIds.has(row.original.positionId)}
      onChange={() => handleToggle(row.original.positionId)}
      label={`Select position ${row.original.tradeId} leg ${row.original.tradeLegId}`}
    />
  ),
  size: 28,
});
```

**Event handlers (internal to component):**

- `handleToggle(positionId)`: Toggles a single position in/out of the selection set. Calls `onSelectionChange` with the new set.
- `handleSelectAll()`: If all selected, deselect all. Otherwise, select all. Calls `onSelectionChange`.
- `handleShiftSelect(positionId, rowIndex)`: Selects the contiguous range from `anchorIndex` to `rowIndex`. The anchor is the last individually toggled row.
- `handleRowBodyClick(row)`: Calls `onRowActivate(row)`. Does NOT change the multi-select set.

**Click target zones:**
- Checkbox cell (28px column): toggle selection.
- Rest of row: activate (drill to L4).
- This is implemented by `onClick` on the checkbox cell calling `handleToggle`, and `onClick` on the `<tr>` calling `handleRowBodyClick`, with `e.stopPropagation()` on the checkbox cell to prevent the row click from firing.

**Keyboard on the row:**
- Space: toggle checkbox (selection).
- Enter: activate row (drill to L4).
- Shift+Space: range select from anchor.
- Ctrl+A: select all / deselect all.
- ArrowUp/ArrowDown: move focus.

**ARIA attributes:**

```html
<div role="grid" aria-label="Position contributions" aria-multiselectable="true" aria-rowcount={N}>
  <table>
    <tr role="row" aria-selected={isSelected} aria-rowindex={i+1} tabIndex={...}>
      <td role="gridcell"><input type="checkbox" aria-label="..." /></td>
      ...
    </tr>
  </table>
</div>
```

**Live region for selection announcements:**

A visually hidden `<div role="status" aria-live="polite" aria-atomic="true">` renders the current selection count. Updated on every selection change: "3 of 20 positions selected" or "All positions deselected".

**Storybook stories:**
- `Default` -- no selection
- `SingleSelected` -- one checkbox checked
- `MultiSelected` -- three checkboxes checked, summary bar visible
- `AllSelected` -- all checkboxes checked, header checkbox checked
- `Loading` -- skeleton with checkbox column
- `Empty` -- empty state

### 5.2 SelectAllCheckbox (new primitive)

**File:** `pv-ui/src/components/primitives/SelectAllCheckbox.tsx`

```typescript
export interface SelectAllCheckboxProps {
  checked: boolean;
  indeterminate: boolean;
  onChange: () => void;
  totalCount: number;
  selectedCount: number;
}
```

Renders a native `<input type="checkbox">` with the `indeterminate` property set via a ref. The `aria-label` reads "Select all N positions" or "Deselect all N positions" based on state.

### 5.3 RowCheckbox (new primitive)

**File:** `pv-ui/src/components/primitives/RowCheckbox.tsx`

```typescript
export interface RowCheckboxProps {
  checked: boolean;
  onChange: () => void;
  label: string; // aria-label for the checkbox
}
```

Renders a native `<input type="checkbox">` with `aria-label`. Styled to match the grid's 24px row height: 14x14px checkbox with `accent-color` from design tokens.

### 5.4 NettedViewBanner (new component)

**File:** `pv-ui/src/components/dashboard/NettedViewBanner.tsx`

```typescript
export interface NettedViewBannerProps {
  selectedCount: number;
  selectedTradeIds: string[]; // for display
  onClear: () => void;
}
```

Displayed above the L4 IntervalDetailPanel when 2+ positions are selected. Shows "Showing netted view for N positions" with a "Clear selection" text button. Optionally shows trade IDs as chips (collapsed if > 3).

Styling: `bg-bg-info border border-border-info rounded px-3 py-2 text-xs text-text-primary`.

ARIA: `role="status"` so screen readers announce it.

### 5.5 MonthViewGrid (modified)

**File:** `pv-ui/src/components/dashboard/MonthViewGrid.tsx`

**Props changes:**

```typescript
export interface MonthViewGridProps {
  data: DailyAggregateDto[] | undefined;
  isLoading: boolean;
  timezone: string;
  selectedDayStart: string | null;  // RENAMED from selectedDay
  selectedDayRange: ReadonlySet<string>; // NEW: set of dayStart values in the range
  onDayClick: (row: DailyAggregateDto) => void;
  onDayShiftClick: (row: DailyAggregateDto) => void; // NEW
}
```

**Visual changes:**
- Rows whose `dayStart` is in `selectedDayRange` get `bg-interactive-row-selected`.
- The anchor row (first clicked) gets a slightly stronger left-border indicator: `border-l-2 border-l-interactive-focus`.

**Event handler changes:**

```typescript
const handleRowClick = (e: React.MouseEvent, row: DailyAggregateDto) => {
  if (e.shiftKey) {
    onDayShiftClick(row);
  } else {
    onDayClick(row);
  }
};
```

**Keyboard changes:**

```typescript
const handleKeyDown = (e: React.KeyboardEvent, row: DailyAggregateDto, rowIndex: number) => {
  if (e.key === 'Enter' || e.key === ' ') {
    e.preventDefault();
    if (e.shiftKey) {
      onDayShiftClick(row);
    } else {
      onDayClick(row);
    }
  } else if (e.key === 'ArrowDown') {
    e.preventDefault();
    if (e.shiftKey) {
      // Extend range to next row
      const nextRow = rows[rowIndex + 1];
      if (nextRow) onDayShiftClick(nextRow);
    }
    rowRefs.current[rowIndex + 1]?.focus();
  } else if (e.key === 'ArrowUp') {
    e.preventDefault();
    if (e.shiftKey) {
      const prevRow = rows[rowIndex - 1];
      if (prevRow) onDayShiftClick(prevRow);
    }
    rowRefs.current[rowIndex - 1]?.focus();
  }
};
```

**ARIA:**
- Grid has `aria-multiselectable="true"`.
- Each row in the selected range has `aria-selected="true"`.
- Live region announces range changes: "Sep 2 to Sep 4 selected (3 days)".

### 5.6 DayBoundaryRow (new presentational component)

**File:** `pv-ui/src/components/dashboard/DayBoundaryRow.tsx`

```typescript
export interface DayBoundaryRowProps {
  dateLabel: string;   // e.g., "03 Sep 2026"
  dstLabel?: string;   // e.g., "23-hour day (DST spring-forward)"
  columnCount: number; // for colspan
}
```

Renders a `<tr>` with a single `<td colSpan={columnCount}>` containing the date label. Styled as a visual separator between days in multi-day sub-daily grids.

- Background: `bg-bg-secondary`.
- Border: `border-t-2 border-border-grid`.
- Font: `text-xs font-semibold text-text-secondary`.
- Height: `h-5` (20px, slightly shorter than data rows).
- Not keyboard-focusable (`tabIndex` not set). Arrow key navigation in the parent grid skips this row.
- `role="presentation"` -- this is a visual separator, not a data row.

### 5.7 SettledDayGrid / ForwardDayGrid / Horizontal variants (modified)

**Props changes (all four components):**

```typescript
// Added to existing props:
isMultiDay: boolean;  // NEW: enables day boundary rendering
timezone: string;     // already present
```

**Behavior when `isMultiDay = true`:**

The grid inserts `DayBoundaryRow` components between intervals that belong to different days. Day membership is determined by comparing the `intervalStart` timestamp converted to the display timezone.

**Implementation approach:** Before rendering, the data array is scanned to identify day boundaries. A stable memo derives the list of `{ type: 'data', interval: ... } | { type: 'boundary', dateLabel: string }` entries. The grid renders this merged list. Virtualization (if added later) must account for the boundary rows in its row count.

### 5.8 IntervalDetailPanel (modified)

**File:** `pv-ui/src/components/dashboard/IntervalDetailPanel.tsx`

**Props changes:**

```typescript
export interface IntervalDetailPanelProps {
  portfolioId: string;
  periodStart: string;
  periodEnd: string;
  positionIds: string[];  // CHANGED from positionId: string | null
  onClose: () => void;
}
```

**Behavior changes:**

1. When `positionIds.length >= 2`, render `NettedViewBanner` above the daily summary.
2. Pass `positionIds` array to `useDailyAggregates`, `useSettledDayDetail`, `useForwardDayDetail` hooks.
3. Header text changes from "Position: abcd1234..." to "Netted view: N positions" when multi-select is active.
4. When `positionIds.length === 0`, the panel fetches portfolio-scoped data (no `positionId` param). This is the "all positions" case.
5. When `positionIds.length === 1`, behavior is identical to current single-position view.

**Day range integration:**

The existing `selectedDay` / `selectedDayStatus` state is replaced by `selectedDayRange` from the updated `useDashboardSelection` store. The panel derives `dayStart` (earliest day in range) and `dayEnd` (latest day's end) from the range and passes them to the sub-daily query hooks.

### 5.9 DashboardPage (modified)

**File:** `pv-ui/src/components/dashboard/DashboardPage.tsx`

**Changes:**

1. Replace `selectedPositionId: string | null` with `selectedPositionIds: Set<string>` and `activatedPositionId: string | null` from the updated store.
2. The condition for rendering `IntervalDetailPanel` changes from `selectedPositionId !== null` to `selectedPositionIds.size > 0 || activatedPositionId !== null`.
3. Compute `positionIds` array to pass to `IntervalDetailPanel`:
   - If `selectedPositionIds.size >= 2`: pass the set as an array (netted view).
   - If `selectedPositionIds.size === 1`: pass the single ID.
   - If `selectedPositionIds.size === 0` and `activatedPositionId !== null`: pass `[activatedPositionId]`.
4. Wire new `onSelectionChange` and `onRowActivate` handlers for `PositionLedger`.
5. **(Feature 3)** Remove the `portfolioId` route prop dependency for the card strip. Add `<PortfolioCardStrip />` between the dashboard header and the filter toolbar. The card strip is self-contained: it fetches portfolio IDs via `usePortfolios()`, fetches summaries for each via `useAllPortfolioSummaries()`, and navigates on card click. The existing `PortfolioSummarySection` (L1 detailed summary for the selected portfolio) remains below the filter bar, unchanged.
6. **(Feature 3)** Remove `PortfolioSelect` from `DashboardFilterBar`. The filter bar retains granularity toggle, date range navigation, and quick filters. The portfolio divider and `<select>` element are deleted.

### 5.10 PortfolioCardStrip (new component)

**File:** `pv-ui/src/components/dashboard/PortfolioCardStrip.tsx`

**Purpose:** Horizontal scrollable container rendering one `PortfolioCard` per portfolio, sorted by absolute total value. Handles keyboard navigation between cards and manages the portfolio selection action.

**Props:**

```typescript
interface PortfolioCardStripProps {
  /** Currently selected portfolio ID (from route param or store) */
  selectedPortfolioId: string;
  /** Callback when user selects a portfolio card */
  onSelectPortfolio: (portfolioId: string) => void;
}
```

**Internal state / hooks:**

- Calls `usePortfolios()` to get the list of portfolio IDs.
- Calls `useAllPortfolioSummaries(portfolioIds, rangeStart, rangeEnd, granularity)` (new hook, see S7) to fetch summary data for every portfolio in parallel.
- Derives sorted card data via `useMemo`: sort by `Math.abs(realizedPnl + unrealizedMtm)` descending. Handles multi-currency portfolios by summing across currencies (this is an approximation for sort-order purposes; each card shows per-currency breakdown).
- Manages focus index internally via `useState` for keyboard navigation within the strip.

**Rendering:**

```tsx
<section aria-label="Portfolio selector">
  <div
    role="listbox"
    aria-label="Portfolios"
    aria-orientation="horizontal"
    className="flex gap-3 overflow-x-auto scroll-smooth snap-x snap-mandatory pb-2"
    onKeyDown={handleKeyDown}
  >
    {sortedPortfolios.map((portfolio, index) => (
      <PortfolioCard
        key={portfolio.portfolioId}
        portfolio={portfolio}
        isSelected={portfolio.portfolioId === selectedPortfolioId}
        onSelect={() => onSelectPortfolio(portfolio.portfolioId)}
        tabIndex={index === focusIndex ? 0 : -1}
        ref={index === focusIndex ? focusRef : undefined}
      />
    ))}
  </div>
</section>
```

**Keyboard handler (`handleKeyDown`):**

```typescript
switch (e.key) {
  case 'ArrowRight': focusIndex = Math.min(focusIndex + 1, count - 1); break;
  case 'ArrowLeft':  focusIndex = Math.max(focusIndex - 1, 0); break;
  case 'Home':       focusIndex = 0; break;
  case 'End':        focusIndex = count - 1; break;
  case 'Enter':
  case ' ':          onSelectPortfolio(sortedPortfolios[focusIndex].portfolioId); break;
}
```

**Skeleton state:** Renders 3 `PortfolioCardSkeleton` components (220px x ~140px with pulsing bars).

**Empty state:** Single card-shaped message: "No portfolios found for this tenant."

**Scroll behavior:** On initial render and on portfolio selection, the selected card is scrolled into view via `element.scrollIntoView({ behavior: 'smooth', inline: 'nearest' })`.

**Accessibility:**
- Container: `role="listbox"`, `aria-label="Portfolios"`, `aria-orientation="horizontal"`.
- Each card: `role="option"`, `aria-selected="true|false"`.
- Roving `tabIndex`: only the focused card has `tabIndex={0}`, others have `tabIndex={-1}`.
- Focus is moved programmatically on arrow key navigation.

**Storybook stories:**
- `Default` -- 3 portfolios, second selected
- `SinglePortfolio` -- 1 portfolio, auto-selected
- `ManyPortfolios` -- 8 portfolios, scroll visible
- `Loading` -- skeleton state
- `Empty` -- no portfolios

### 5.11 PortfolioCard (new component)

**File:** `pv-ui/src/components/dashboard/PortfolioCard.tsx`

**Purpose:** Individual portfolio summary card within the card strip. Displays key metrics at a glance.

**Props:**

```typescript
interface PortfolioCardProps {
  /** Aggregated portfolio data for display */
  portfolio: PortfolioCardData;
  /** Whether this card is the selected portfolio */
  isSelected: boolean;
  /** Click handler to select this portfolio */
  onSelect: () => void;
  /** Roving tabIndex for keyboard navigation */
  tabIndex: number;
}

/** Aggregated summary for one portfolio, derived from PortfolioSummaryDto[] */
interface PortfolioCardData {
  portfolioId: string;
  /** Display label (from KNOWN_PORTFOLIOS lookup or portfolioId itself) */
  label: string;
  /** Per-currency summaries */
  currencies: Array<{
    currency: string;
    realizedPnl: number;
    unrealizedMtm: number;
    totalValue: number;
    settledNetMw: number;
    settledNetMwh: number;
    forwardNetMw: number;
    forwardNetMwh: number;
  }>;
  /** Absolute total value across all currencies (for sort order). Approximation: sum of |totalValue| per currency. */
  absTotalValue: number;
  /** Timestamp of freshest data across currencies */
  dataAsOf: string;
}
```

**Rendering:**

```tsx
<div
  role="option"
  aria-selected={isSelected}
  aria-label={`${portfolio.label}: total value ${formatCurrency(portfolio.currencies[0]?.totalValue, portfolio.currencies[0]?.currency)}`}
  tabIndex={tabIndex}
  onClick={onSelect}
  onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onSelect(); } }}
  className={cn(
    'w-[220px] flex-shrink-0 snap-start rounded-lg border p-3 space-y-2 cursor-pointer',
    'transition-all duration-150',
    isSelected
      ? 'border-interactive-focus border-2 bg-interactive-row-selected shadow-sm scale-[1.02]'
      : 'border-border-default bg-bg-secondary hover:border-border-hover hover:bg-bg-hover',
  )}
>
  {/* Header: portfolio name + selection dot */}
  <div className="flex items-center justify-between">
    <span className="text-sm font-semibold text-text-primary truncate">{portfolio.label}</span>
    {isSelected && <span className="w-2 h-2 rounded-full bg-interactive-focus" aria-hidden="true" />}
  </div>

  {/* Per-currency metrics (typically 1 currency; show all if multiple) */}
  {portfolio.currencies.map((c) => (
    <div key={c.currency} className="space-y-1">
      {/* Total value -- bold, prominent */}
      <div className="text-base font-bold font-mono text-text-primary text-right">
        <FormattedNumber value={c.totalValue} precision="MONETARY" /> {c.currency}
      </div>

      {/* Realized / Unrealized split */}
      <div className="grid grid-cols-2 gap-x-2 text-xs">
        <span className="text-text-muted">Realized</span>
        <span className="text-right font-mono"><FormattedNumber value={c.realizedPnl} precision="MONETARY" /></span>
        <span className="text-text-muted">Unrealized</span>
        <span className="text-right font-mono"><FormattedNumber value={c.unrealizedMtm} precision="MONETARY" /></span>
      </div>

      {/* MW / MWh row */}
      <div className="flex justify-between text-xs text-text-secondary">
        <span>MW: {formatMw(c.settledNetMw)}/{formatMw(c.forwardNetMw)}</span>
        <span>MWh: {formatMwh(c.settledNetMwh)}/{formatMwh(c.forwardNetMwh)}</span>
      </div>
    </div>
  ))}

  {/* Data freshness */}
  <div className="text-[10px] text-text-muted text-right">
    {formatDataAge(portfolio.dataAsOf)}
  </div>
</div>
```

**Visual states:**

| State | Appearance |
|-------|------------|
| Default (unselected) | `border-border-default`, `bg-bg-secondary` |
| Hover (unselected) | `border-border-hover`, `bg-bg-hover` |
| Focus (keyboard) | Focus ring via `focus-visible:ring-2 ring-interactive-focus` |
| Selected | `border-interactive-focus` (2px), `bg-interactive-row-selected`, subtle `scale-[1.02]`, selection dot |
| Selected + Focus | Combined selected + focus ring styles |
| Error | Portfolio name visible, metrics replaced with error icon + "Failed to load" text |
| Loading (skeleton) | Pulsing bars at `w-[220px]` matching metric layout |

**Number formatting:** All monetary values use `Intl.NumberFormat` with MONETARY precision (4 decimal places). MW values use 2 decimal places. MWh values use 0 decimal places. Right-aligned, monospace font for numeric parts.

**Negative numbers:** Displayed in `text-numeric-negative` color and paired with a minus sign (not parenthesized in the compact card view).

**Storybook stories:**
- `Selected` -- highlighted card
- `Unselected` -- default card
- `MultipleCurrencies` -- card with EUR and GBP sections
- `NegativeValues` -- card with negative PnL
- `Error` -- failed to load
- `Skeleton` -- loading placeholder

---

## S6 -- State

### 6.1 Zustand Store: `useDashboardSelection` (extended)

**File:** `pv-ui/src/hooks/useDashboardSelection.ts`

**New state shape (additions to existing):**

```typescript
interface DashboardSelectionState {
  // --- Existing L2 period selection (unchanged) ---
  selectedPeriod: { start: string; end: string; status: PeriodStatus } | null;
  anchorPeriodStart: string | null;
  selectedRangeStarts: string[];

  // --- L3 position selection (CHANGED) ---
  selectedPositionIds: Set<string>;       // NEW: multi-select set
  positionAnchorId: string | null;        // NEW: anchor for Shift+Space range select
  activatedPositionId: string | null;     // RENAMED from selectedPositionId

  // --- L4 day selection (CHANGED) ---
  selectedDay: string | null;             // existing -- now serves as range start anchor
  selectedDayEnd: string | null;          // NEW: range end dayStart value (null = single day)
  selectedDayRange: Set<string>;          // NEW: set of dayStart values in the range
  selectedDayStatus: 'SETTLED' | 'TODAY' | 'FORWARD' | null; // existing

  // --- Actions ---
  // Existing actions unchanged...
  setSelectedPosition: (id: string | null) => void; // RENAMED to setActivatedPosition
  setActivatedPosition: (id: string | null) => void;

  // NEW actions:
  togglePositionSelection: (positionId: string) => void;
  setPositionSelection: (positionIds: Set<string>) => void;
  selectAllPositions: (allPositionIds: string[]) => void;
  deselectAllPositions: () => void;
  shiftSelectPosition: (positionId: string, allPositionIds: string[]) => void;
  setDayRange: (
    anchorDay: string,
    endDay: string,
    allDays: DailyAggregateDto[],
  ) => void;
  clearDayRange: () => void;
}
```

**Selection cascading rules (critical):**

1. When `selectedPeriod` changes (L2 row click), clear `selectedPositionIds`, `activatedPositionId`, `selectedDay*` -- existing cascade, extended to new fields.
2. When `selectedPositionIds` changes, clear `selectedDay*` (day selection is position-scoped, so changing the position set invalidates day-level selections).
3. When `activatedPositionId` changes, do NOT clear `selectedPositionIds` (activation and selection are independent).
4. When day selection changes, no upward cascade.

**`togglePositionSelection(positionId)` logic:**

```
if set contains positionId:
  remove it; set positionAnchorId = null
else:
  add it; set positionAnchorId = positionId
clear day selection (cascade rule 2)
```

**`shiftSelectPosition(positionId, allPositionIds)` logic:**

```
if positionAnchorId is null:
  treat as toggle (set anchor, add to set)
else:
  find anchorIndex and targetIndex in allPositionIds
  select all IDs from min(anchorIndex, targetIndex) to max(anchorIndex, targetIndex)
  merge into existing set (additive range select)
  do NOT move anchor (anchor stays for subsequent Shift+Clicks)
clear day selection (cascade rule 2)
```

**`setDayRange(anchorDay, endDay, allDays)` logic:**

```
find anchorIdx and endIdx in allDays (by dayStart match)
clamp range to 31 days
derive selectedDayRange = set of dayStart values from min to max index
derive selectedDayStatus from combined statuses of days in range
set selectedDay = earliest day's dayStart
set selectedDayEnd = latest day's dayStart
```

### 6.1a Zustand Store: `useDashboardFilters` (modified for Feature 3)

**File:** `pv-ui/src/hooks/useDashboardFilters.ts`

**Changes:**

1. The `portfolioId` field and `setPortfolioId` action remain in this store. Portfolio selection is still driven by `useDashboardFilters.portfolioId`.
2. No new state fields needed. The card strip reads `portfolioId` from this store and writes to it on card click (plus navigating the route).
3. The `PortfolioSelect` component in `DashboardFilterBar` is removed. The card strip in `DashboardPage` replaces it as the sole portfolio selection UI.

**Portfolio selection flow (Feature 3):**

```
PortfolioCardStrip → user clicks card
  → onSelectPortfolio(id)
    → navigate({ to: '/dashboard/$portfolioId', params: { portfolioId: id } })
    → route param change triggers DashboardPage re-render
    → useEffect syncs route param → useDashboardFilters.setPortfolioId(id)
    → existing cascade: setPortfolioId clears drill-down state
    → L1/L2/L3/L4 queries refetch with new portfolioId
```

This preserves the existing data flow. The card strip is a presentation-layer replacement of the dropdown; the state plumbing is unchanged.

### 6.2 Query Key Changes

**File:** `pv-ui/src/api/queryKeys.ts`

**Feature 3 additions:**

A new query key factory for fetching all portfolio summaries (one per portfolio):

```typescript
allPortfolioSummaries: (tenantId: string) =>
  [...dashboardKeys.all, 'all-summaries', tenantId] as const,

portfolioSummaryForCard: (
  tenantId: string,
  portfolioId: string,
  rangeStart: string,
  rangeEnd: string,
  granularity: TimeGranularity,
) =>
  [...dashboardKeys.all, 'all-summaries', tenantId, portfolioId, rangeStart, rangeEnd, granularity] as const,
```

The `all-summaries` prefix allows bulk invalidation of all card data when SSE fires `DashboardDataChangedEvent`.

**Existing query key changes (Features 1 & 2):**

The `dailyAggregates`, `settledDay`, and `forwardDay` key factories currently accept `positionId?: string`. They must change to accept `positionIds: string[]` (sorted, for cache key stability):

```typescript
dailyAggregates: (
  tenantId: string,
  portfolioId: string,
  monthStart: string,
  monthEnd: string,
  positionIds: string[],
) =>
  [...dashboardKeys.all, 'daily', tenantId, portfolioId, monthStart, monthEnd,
   positionIds.length > 0 ? positionIds.slice().sort().join(',') : 'all'] as const,

settledDay: (
  tenantId: string,
  portfolioId: string,
  dayStart: string,
  dayEnd: string,
  granularity: SubDailyGranularity,
  positionIds: string[],
) =>
  [...dashboardKeys.all, 'settled-day', tenantId, portfolioId, dayStart, dayEnd, granularity,
   positionIds.length > 0 ? positionIds.slice().sort().join(',') : 'all'] as const,

forwardDay: (
  tenantId: string,
  portfolioId: string,
  dayStart: string,
  dayEnd: string,
  granularity: SubDailyGranularity,
  positionIds: string[],
) =>
  [...dashboardKeys.all, 'forward-day', tenantId, portfolioId, dayStart, dayEnd, granularity,
   positionIds.length > 0 ? positionIds.slice().sort().join(',') : 'all'] as const,
```

**Cache key stability:** `positionIds` is sorted before joining to ensure that `{A, B}` and `{B, A}` produce the same cache key. The join uses `,` as a delimiter (UUIDs never contain commas).

### 6.3 URL State

No URL state changes. Multi-select is ephemeral. Deep-linking to a specific multi-position netted view is a future enhancement (would require `?positionIds=uuid1,uuid2` search params via TanStack Router).

---

## S7 -- Data Contract

### 7.1 API Function Changes

**File:** `pv-ui/src/api/dashboard.ts`

#### 7.1.1 `apiFetch` Enhancement

The current `apiFetch` uses `url.searchParams.set(key, value)` which only supports single-value params. For repeatable `positionId` params, a new helper or direct `URL` manipulation is needed.

**Option chosen:** Add a `paramsMulti` argument to `apiFetch` for repeated params, or build the URL manually in the fetch functions. To minimize changes to the shared `apiFetch` client, the dashboard fetch functions will build the `positionId` params directly:

```typescript
// New helper in dashboard.ts (not exported, module-private):
function appendPositionIds(url: URL, positionIds: string[]): void {
  for (const id of positionIds) {
    url.searchParams.append('positionId', id);
  }
}
```

Alternatively, `apiFetch` can be extended to accept `Record<string, string | string[] | number | boolean | undefined>` where arrays produce repeated params. This is the cleaner approach and benefits future endpoints:

```typescript
// In client.ts, change params type:
export async function apiFetch<T>(
  path: string,
  params: Record<string, string | number | boolean | string[] | undefined>,
  tenantId: string,
): Promise<T> {
  const url = new URL(`${BASE_URL}${path}`, window.location.origin);
  url.searchParams.set('tenantId', tenantId);
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined) continue;
    if (Array.isArray(value)) {
      for (const v of value) {
        url.searchParams.append(key, v);
      }
    } else {
      url.searchParams.set(key, String(value));
    }
  }
  // ... rest unchanged
}
```

**Recommendation:** Extend `apiFetch` to support arrays. This is a backward-compatible change.

#### 7.1.1a `useAllPortfolioSummaries` Hook (new, Feature 3)

**File:** `pv-ui/src/hooks/useDashboardQueries.ts`

**Purpose:** Fetches portfolio summary data for ALL portfolios in parallel, used by `PortfolioCardStrip` to populate the cards.

```typescript
/**
 * Fetches portfolio summaries for all given portfolio IDs in parallel.
 * Uses `useQueries` to issue one `fetchPortfolioSummary` call per portfolio.
 * Results are combined into a single array of { portfolioId, data, isLoading, isError }.
 */
export function useAllPortfolioSummaries(
  portfolioIds: string[],
  rangeStart: string,
  rangeEnd: string,
  granularity: TimeGranularity,
) {
  const tenantId = useTenantStore((s) => s.tenantId);

  return useQueries({
    queries: portfolioIds.map((portfolioId) => ({
      queryKey: dashboardKeys.portfolioSummaryForCard(
        tenantId, portfolioId, rangeStart, rangeEnd, granularity,
      ),
      queryFn: () => fetchPortfolioSummary(
        tenantId, portfolioId, rangeStart, rangeEnd, granularity,
      ),
      enabled: !!tenantId && !!portfolioId,
      staleTime: 30_000,
      gcTime: 300_000,
      refetchInterval: 120_000,
    })),
  });
}
```

**Key design decisions:**

1. **Reuses `fetchPortfolioSummary`** -- the existing API endpoint `GET /api/dashboard/portfolios/{id}/summary` is called once per portfolio. No new backend endpoint needed. For 3-5 portfolios this is 3-5 parallel HTTP requests, which is acceptable. For 10+ portfolios, a batch endpoint should be considered (see OI-7).

2. **`useQueries` from TanStack Query v5** -- issues all requests in parallel and returns an array of query results. Each result can independently be loading, error, or success. Cards render progressively as results arrive.

3. **Query key uses `portfolioSummaryForCard`** -- distinct from `portfolioSummary` to avoid cache collisions with the L1 detailed summary (which may use different date ranges). Both share the `all-summaries` prefix for bulk SSE invalidation.

4. **SSE invalidation** -- when `DashboardDataChangedEvent` fires, the existing prefix-based invalidation on `dashboardKeys.all` will invalidate all card queries. This triggers a refetch of all card summaries, ensuring cards stay up to date with real-time position/settlement changes.

#### 7.1.2 `fetchDailyAggregates` Signature Change

```typescript
export async function fetchDailyAggregates(
  tenantId: string,
  portfolioId: string,
  monthStart: string,
  monthEnd: string,
  positionIds: string[] = [],  // CHANGED from positionId?: string
  timezone = 'Europe/Berlin',
): Promise<DailyAggregateDto[]> {
  const raw = await apiFetch<unknown>(
    `/api/dashboard/portfolios/${encodeURIComponent(portfolioId)}/daily`,
    {
      monthStart,
      monthEnd,
      positionId: positionIds.length > 0 ? positionIds : undefined,
      timezone,
    },
    tenantId,
  );
  // ... Zod validation unchanged
}
```

#### 7.1.3 `fetchSettledDayDetail` Signature Change

```typescript
export async function fetchSettledDayDetail(
  tenantId: string,
  portfolioId: string,
  dayStart: string,
  dayEnd: string,            // now represents range end, not single day end
  granularity: SubDailyGranularity,
  positionIds: string[] = [],  // CHANGED from positionId?: string
): Promise<SettlementCellDto[]> {
  const raw = await apiFetch<unknown>(
    '/api/dashboard/settlements/day',
    {
      portfolioId,
      dayStart,
      dayEnd,
      granularity,
      positionId: positionIds.length > 0 ? positionIds : undefined,
    },
    tenantId,
  );
  // ... Zod validation unchanged
}
```

#### 7.1.4 `fetchForwardDayDetail` Signature Change

Same pattern as `fetchSettledDayDetail`.

### 7.2 Backend Endpoints Consumed

All endpoint paths and response shapes are unchanged. The only wire-format change is the addition of repeatable `positionId` query params and wider `dayStart`/`dayEnd` ranges.

| Endpoint | Change | Feature |
|----------|--------|---------|
| `GET /api/dashboard/portfolios` | No change. Returns `string[]` of portfolio IDs. | F3 (used by card strip to discover portfolios) |
| `GET /api/dashboard/portfolios/{id}/summary` | No change. Called once per portfolio for card strip data. | F3 (parallel calls for all portfolios) |
| `GET /api/dashboard/portfolios/{id}/daily` | `positionId` becomes repeatable | F1 |
| `GET /api/dashboard/settlements/day` | `positionId` becomes repeatable; `dayStart`/`dayEnd` may span multiple days | F1, F2 |
| `GET /api/dashboard/forward/day` | Same as above | F1, F2 |

Response schemas are unchanged. The backend returns netted aggregates when multiple `positionId` params are provided (S15.2 of trade-leg-rollup-v1.0).

**Feature 3 note:** No new backend endpoints are needed. The card strip reuses `GET /api/dashboard/portfolios` (existing) and `GET /api/dashboard/portfolios/{id}/summary` (existing, called N times in parallel). If the portfolio count grows beyond ~10, a batch summary endpoint should be considered (see OI-7).

---

## S8 -- Real-time & Bitemporal

### 8.1 SSE Invalidation

The existing `useRealtimeInvalidation` hook invalidates TanStack Query cache keys when the backend pushes `DashboardDataChangedEvent` via SSE. No changes needed for Features 1 and 2. When the backend recomputes settlement cells for any position in the selected set, the SSE event triggers a refetch of the L4 queries. The new query keys (with `positionIds` in the key) will be invalidated by the existing prefix-based invalidation (`dashboardKeys.all`).

**Feature 3 SSE behavior:** The prefix-based invalidation on `dashboardKeys.all` also invalidates the `all-summaries` query keys used by the portfolio card strip. This means all card summaries refresh when any `DashboardDataChangedEvent` fires. This is correct behavior -- a settlement recomputation can change any portfolio's realized PnL or unrealized MtM, so all cards must refresh. The card sort order may change after a refresh if a portfolio's total value crosses another's.

### 8.2 As-Of Toggle

Not applicable for this spec. The as-of toggle affects the queries themselves (bitemporal filtering on the backend). The multi-select UI changes do not interact with bitemporal views.

### 8.3 Staleness

Staleness times are unchanged:
- `dailyAggregates`: 30s staleTime
- `settledDayDetail`: 120s staleTime (settled data is stable)
- `forwardDayDetail`: 15s staleTime (forward marks are volatile)

When the user changes the position selection set, the query key changes, causing TanStack Query to refetch. The old query data remains in the cache (keyed by the old `positionIds`) and is garbage-collected after `gcTime` (5 minutes). This means switching between "positions A+B" and "positions A+B+C" has instant cache hits on the second visit within 5 minutes.

---

## S8a -- Performance

### 8a.1 Multi-Position Queries

The server-side netted aggregate returns the same number of rows regardless of how many positions are selected (aggregation collapses positions). Response sizes:

| Scenario | Daily aggs (rows) | Sub-daily 1 day (rows) | Sub-daily 5 days (rows) |
|----------|-------------------|------------------------|-------------------------|
| 1 position | ~30 | ~96 | ~480 |
| 10 positions (netted) | ~30 | ~96 | ~480 |

No client-side performance concern. The position count affects server query time (more rows to aggregate), not client rendering cost.

### 8a.2 Day Range Sub-Daily Grid

At MAX_15 granularity, 31 days = 2,976 intervals (worst case, assuming no DST transitions reduce the count). This is within the range where TanStack Table with `@tanstack/react-virtual` is recommended but not strictly required. The current grids do not use virtualization (they handle at most 96-100 rows per day).

**Decision:** For the initial implementation, render all rows without virtualization when the day range is <= 7 days (max 672 rows). For ranges > 7 days, show HOURLY aggregation by default and allow the user to switch to MIN_15 with a warning tooltip: "Large interval count -- rendering may be slow."

**Follow-on:** Add `@tanstack/react-virtual` to sub-daily grids when day range support is proven useful. Tracked as a TODO, not a blocker.

### 8a.3 Selection State Performance

`selectedPositionIds` is a `Set<string>` with at most ~200 entries (per A-4 in trade-leg-rollup-v1.0). `.has()` checks are O(1). No performance concern.

`selectedDayRange` is a `Set<string>` with at most 31 entries. No concern.

### 8a.4 Memoization

- The `positionIds` array passed to query hooks must be referentially stable. Derive it via `useMemo` from `selectedPositionIds` Set, sorted, so that the same set of IDs always produces the same array reference.
- Day boundary computation for multi-day grids: memoize via `useMemo` keyed on `data` reference.

### 8a.5 Bundle Splitting

No new heavy dependencies. No bundle splitting changes needed.

### 8a.6 Portfolio Card Strip Performance (Feature 3)

**Parallel fetch cost:** For N portfolios, the card strip issues N parallel `GET /api/dashboard/portfolios/{id}/summary` requests on mount. For the expected range (3-10 portfolios), this is N lightweight requests returning small payloads (~200 bytes each). HTTP/2 multiplexing handles this efficiently.

**Rendering cost:** N cards at 220px each. For N <= 20, no virtualization needed. The horizontal scroll container renders all cards in the DOM. If N exceeds 20 (unlikely for CTRM portfolios), horizontal virtualization should be added.

**Sort recomputation:** `useMemo` keyed on the combined query results. Sorting N items by absolute value is O(N log N), negligible for N <= 100.

**Stale-while-revalidate:** Card data uses the same `staleTime: 30_000` as the L1 portfolio summary. When navigating between portfolios (clicking cards), the card strip does NOT refetch -- only the L1/L2/L3/L4 sections refetch for the newly selected portfolio. Card data is already in cache.

---

## S8b -- DST Handling

### 8b.1 Day Boundary Rows in Multi-Day Sub-Daily Grid

Day boundaries are determined by converting each interval's `intervalStart` from UTC to the display timezone and comparing the date component. On DST transition days:

- **Spring-forward (23-hour day):** The day boundary row shows "30 Mar 2026 (23h, DST spring-forward)". The grid renders 92 intervals for that day, skipping from 01:45 CET to 03:00 CEST.
- **Fall-back (25-hour day):** The day boundary row shows "25 Oct 2026 (25h, DST fall-back)". The grid renders 100 intervals. The duplicate 02:00 hour is labeled `02:00 (CEST)` and `02:00 (CET)` per the existing convention in the sub-daily grids.

### 8b.2 Day Range API Boundaries

When the user selects a day range, the `dayStart` and `dayEnd` values sent to the API are UTC timestamps. These come directly from the `DailyAggregateDto.dayStart` and `DailyAggregateDto.dayEnd` fields, which are already UTC-aligned by the backend. The UI does NOT re-derive UTC boundaries from local dates for this purpose -- it uses the values from the daily aggregate response.

This avoids the DST pitfall described in the system prompt (local midnight conversion). The daily aggregate response already has correct UTC boundaries for each day, including DST transition days.

### 8b.3 Day Count for 31-Day Limit

The 31-day limit is enforced by counting the number of day rows in the selected range (i.e., `selectedDayRange.size`), not by computing a calendar day difference. This correctly handles ranges that include DST transitions without off-by-one errors.

---

## S9 -- Accessibility

### 9.1 L3 PositionLedger Multi-Select

| Requirement | Implementation |
|-------------|----------------|
| `aria-multiselectable="true"` | Set on the grid container `<div role="grid">` |
| `aria-selected` per row | Set to `true` on rows whose checkbox is checked |
| Checkbox labeling | Each checkbox has `aria-label="Select position T-7788 leg L1"` |
| Header checkbox labeling | `aria-label="Select all 20 positions"` or `"Deselect all 20 positions"` |
| Selection count announcement | `<div role="status" aria-live="polite">3 of 20 positions selected</div>` |
| Keyboard: Space | Toggles checkbox on focused row |
| Keyboard: Shift+Space | Range-selects from anchor to focused row |
| Keyboard: Ctrl+A | Select all / deselect all |
| Keyboard: Enter | Activates row (drill to L4) -- distinct from selection |
| Keyboard: Escape | Clears multi-selection if active; otherwise closes panel |
| Focus management | Roving tabindex on rows (existing). Checkbox is part of the row and does not take independent tab focus (the row handles Space for the checkbox). |
| Contrast | Checkbox uses native browser rendering with `accent-color` from design tokens. Meets 3:1 contrast for UI components. |

### 9.2 L4 MonthViewGrid Range Select

| Requirement | Implementation |
|-------------|----------------|
| `aria-multiselectable="true"` | Set on the grid container |
| `aria-selected` per row | Set to `true` on rows in the selected range |
| Range announcement | `<div role="status" aria-live="polite">Sep 2 to Sep 4 selected, 3 days</div>` |
| Keyboard: Shift+Enter or Shift+Space | Extends range from anchor to focused row |
| Keyboard: Shift+ArrowDown/Up | Extends range by one row and moves focus |
| Keyboard: Enter/Space (no Shift) | Resets to single-day selection |
| Keyboard: Escape | Clears day selection |
| Focus ring | Visible on focused row (existing `focus-visible:ring-2`) |

### 9.3 Day Boundary Rows

Day boundary rows in the sub-daily grid have `role="presentation"` and are skipped by arrow key navigation. Screen readers will encounter them as presentational separators. The date label is readable but not focusable.

### 9.4 NettedViewBanner

The banner has `role="status"` and uses `aria-live="polite"`. When the user changes the position selection, the banner text is announced: "Showing netted view for 3 positions".

### 9.5 Portfolio Card Strip (Feature 3)

The portfolio card strip follows the **ARIA Listbox** pattern:

- **Container:** `role="listbox"`, `aria-label="Portfolios"`, `aria-orientation="horizontal"`.
- **Cards:** `role="option"`, `aria-selected="true"` on the selected card, `aria-selected="false"` on others.
- **Roving tabIndex:** Only the currently focused card has `tabIndex={0}`. All others have `tabIndex={-1}`. This keeps the card strip as a single Tab stop -- the user tabs in, navigates with arrow keys, and tabs out.
- **Arrow key navigation:** `ArrowLeft` / `ArrowRight` move focus between cards. `Home` / `End` jump to first / last card. Focus is moved programmatically via `ref.focus()`.
- **Selection:** `Enter` or `Space` selects the focused card. The selection triggers navigation to that portfolio.
- **Screen reader announcements:** Each card's `aria-label` includes the portfolio name and total value, e.g. `"Wind DE/LU: total value 142,350.00 EUR"`. This ensures a screen reader user can identify the card without reading the full visual layout.
- **Focus ring:** Cards use `focus-visible:ring-2 ring-interactive-focus` for a visible focus indicator. The ring is visible on keyboard focus only (not on mouse click).
- **Selected indicator:** The selected card has a 2px `border-interactive-focus` border AND a small filled dot icon. Color is not the only indicator -- the dot and border weight distinguish selected from unselected.

### 9.6 Contrast Verification

All new UI elements use existing design tokens (`text-text-primary`, `bg-interactive-row-selected`, etc.) which are verified at 4.5:1 contrast in both light and dark themes. The checkbox column uses native browser checkbox rendering which meets WCAG contrast requirements by default. Portfolio card text (`text-sm`, `text-xs`) meets 4.5:1 minimum in both themes.

---

## S10 -- Testing

### 10.1 Unit Tests (Vitest + React Testing Library)

**`PositionLedger.test.tsx`:**
1. Renders checkbox column with correct aria-labels.
2. Clicking checkbox toggles `onSelectionChange` with updated set.
3. Shift+Space on a row selects contiguous range.
4. Ctrl+A selects all, then deselects all.
5. Clicking row body (not checkbox) calls `onRowActivate`, not `onSelectionChange`.
6. Header checkbox shows indeterminate state when partially selected.
7. Selection count live region updates text on selection change.
8. Escape clears selection when multi-select is active.

**`MonthViewGrid.test.tsx`:**
1. Shift+Click selects a contiguous day range.
2. Click without Shift resets to single-day selection.
3. Shift+ArrowDown extends range by one day.
4. Range is clamped to 31 days (Shift+Click beyond 31 days is clamped).
5. `aria-selected` is set on all rows in the range.
6. Live region announces range.

**`NettedViewBanner.test.tsx`:**
1. Renders correct count text.
2. Clear button calls `onClear`.
3. Has `role="status"`.

**`SelectAllCheckbox.test.tsx`:**
1. Renders checked state.
2. Renders indeterminate state.
3. Click triggers `onChange`.
4. Aria-label includes count.

**`useDashboardSelection.test.ts`:**
1. `togglePositionSelection` adds and removes IDs.
2. `shiftSelectPosition` selects contiguous range.
3. `selectAllPositions` sets all IDs.
4. `deselectAllPositions` clears set.
5. Changing `selectedPositionIds` clears day selection (cascade rule 2).
6. Changing `selectedPeriod` clears position selection (cascade rule 1).
7. `setDayRange` computes correct range set.
8. `setDayRange` clamps to 31 days.

**`dashboard.ts` (API functions):**
1. `fetchDailyAggregates` with `positionIds = ['a', 'b']` produces URL with `?positionId=a&positionId=b`.
2. `fetchDailyAggregates` with `positionIds = []` produces URL without `positionId` param.
3. `fetchSettledDayDetail` with multi-day range sends correct `dayStart`/`dayEnd`.

**`PortfolioCardStrip.test.tsx` (Feature 3):**
1. Renders one card per portfolio from `usePortfolios` data.
2. Cards are sorted by absolute total value descending.
3. Clicking a card calls `onSelectPortfolio` with the correct portfolio ID.
4. Selected card has `aria-selected="true"`.
5. ArrowRight moves focus to next card.
6. ArrowLeft moves focus to previous card.
7. Home moves focus to first card.
8. End moves focus to last card.
9. Enter on focused card calls `onSelectPortfolio`.
10. Space on focused card calls `onSelectPortfolio`.
11. Shows skeleton cards while loading.
12. Shows empty state when no portfolios available.

**`PortfolioCard.test.tsx` (Feature 3):**
1. Renders portfolio name, total value, realized PnL, unrealized MtM.
2. Renders MW and MWh values.
3. Renders currency badge.
4. Selected state applies correct CSS classes (border, background, scale).
5. Unselected state applies default CSS classes.
6. Click triggers `onSelect`.
7. Negative values render in `text-numeric-negative`.
8. Multiple currencies render separate sections.
9. Error state renders portfolio name with "Failed to load" message.
10. Has `role="option"` and correct `aria-selected`.

**`useAllPortfolioSummaries.test.ts` (Feature 3):**
1. Issues one query per portfolio ID.
2. Returns loading state while any query is pending.
3. Returns combined data when all queries succeed.
4. Handles partial failures (some portfolios error, others succeed).
5. Uses correct query keys with `portfolioSummaryForCard` factory.

### 10.2 Storybook Stories

**PortfolioCardStrip (Feature 3):**
- `Default` -- 3 portfolios, second selected
- `SinglePortfolio` -- 1 portfolio, auto-selected
- `ManyPortfolios` -- 8 portfolios, horizontal scroll visible
- `Loading` -- 3 skeleton cards
- `Empty` -- no portfolios message
- `PartialError` -- 1 of 3 cards shows error state

**PortfolioCard (Feature 3):**
- `Selected` -- highlighted with border and dot
- `Unselected` -- default card
- `MultipleCurrencies` -- EUR and GBP sections
- `NegativeValues` -- negative PnL values in red
- `Error` -- failed to load state
- `Skeleton` -- loading placeholder

**PositionLedger:**
- `NoSelection` -- checkboxes all unchecked
- `SingleCheckboxSelected` -- one checked, no netted banner
- `MultipleSelected` -- three checked, netted banner visible
- `AllSelected` -- header checkbox checked
- `Loading` -- skeleton with checkbox column

**MonthViewGrid:**
- `SingleDaySelected` -- one row highlighted
- `DayRangeSelected` -- three contiguous rows highlighted
- `MaxRangeSelected` -- 31 rows highlighted

**NettedViewBanner:**
- `FewPositions` -- 3 positions, trade IDs shown
- `ManyPositions` -- 10 positions, trade IDs collapsed

### 10.3 E2E (Playwright)

**Journey 1: Multi-select positions and view netted aggregate**
1. Navigate to dashboard.
2. Click a period in L2 to open L3.
3. Check two position checkboxes in L3.
4. Verify NettedViewBanner shows "Showing netted view for 2 positions".
5. Verify L4 daily summary loads (API call includes two `positionId` params).
6. Click a day in L4.
7. Verify sub-daily grid loads netted data.

**Journey 2: Day range selection**
1. Navigate to dashboard, open L3, activate a position.
2. In L4 MonthViewGrid, click day 3.
3. Shift+Click day 7.
4. Verify 5 rows are highlighted.
5. Verify sub-daily grid header shows "Sep 3 -- Sep 7, 2026".
6. Verify sub-daily grid has day boundary separators.

**Journey 3: Keyboard-only multi-select**
1. Tab to L3 grid.
2. ArrowDown to second row.
3. Space to check checkbox.
4. ArrowDown to fourth row.
5. Shift+Space to range-select rows 2-4.
6. Verify 3 checkboxes are checked.
7. Verify live region announces "3 of N positions selected".

**Journey 4: Portfolio card selection (Feature 3)**
1. Navigate to `/dashboard/WIND_DE`.
2. Verify card strip is visible with all portfolio cards.
3. Verify WIND_DE card has `aria-selected="true"`.
4. Verify cards are sorted by total value (highest first).
5. Click the SOLAR_DE card.
6. Verify URL changes to `/dashboard/SOLAR_DE`.
7. Verify SOLAR_DE card now has `aria-selected="true"`, WIND_DE has `aria-selected="false"`.
8. Verify L1 summary section loads for SOLAR_DE.
9. Verify L2 rollup grid loads for SOLAR_DE.
10. Verify any previously open L3/L4 panels are closed (drill-down state cleared).

**Journey 5: Portfolio card keyboard navigation (Feature 3)**
1. Navigate to `/dashboard/WIND_DE`.
2. Tab to the portfolio card strip.
3. Verify first card (highest value) is focused.
4. Press ArrowRight to move focus to second card.
5. Press Enter to select the second card.
6. Verify URL changes to the second portfolio's ID.
7. Verify dashboard loads for the new portfolio.

---

## S11 -- Open Items

| # | Question | Who Decides | Impact if Unresolved |
|---|----------|-------------|----------------------|
| OI-1 | Should multi-position selection persist across period (L2) changes? Current cascade rule clears position selection. An alternative is to preserve the selection if the same positions exist in the new period. | Product | If cleared: simpler, no stale-selection risk. If preserved: better UX for traders comparing the same trades across months. Recommend: clear (cascade rule 1). |
| OI-2 | Should "Select All" in L3 select all positions including those not yet loaded (if pagination is added later)? | Product / UI architect | Current design: select all visible rows. If pagination lands (OI from trade-leg-rollup-v1.0), this becomes "select all on current page" vs "select all across all pages". Defer until pagination is implemented. |
| OI-3 | Should the sub-daily grid switch to HOURLY default for day ranges > 7 days, or let the user choose freely and accept potential rendering slowness? | Product | Recommend: default to HOURLY for > 7 days, allow override with warning. |
| OI-4 | When selected positions have mixed delivery statuses (some SETTLED, some FORWARD), what does the L4 day grid show? The backend returns netted aggregates regardless. The question is whether the UI should show a warning. | Product | The backend aggregation is well-defined (FR-035 applies uniformly). The UI should show a PARTIAL status badge and no warning. The netted values are mathematically correct. |
| OI-5 | When a day range crosses the SETTLED/FORWARD boundary, the sub-daily grid must show settlement cells for settled days and forward marks for forward days. Should this be two separate grids (split view) or one merged grid with different cell styling? | Product / UI architect | Recommend: single grid with a status column. Settled rows show actual prices; forward rows show curve prices with an "(indicative)" suffix. Day boundary rows additionally show the status transition. |
| OI-6 | Maximum number of positions selectable? The backend handles any count, but large selections (50+) produce dense netted aggregates that may be hard to interpret. | Product | Recommend: no hard limit. The "Select All" checkbox is the natural upper bound (~200 positions per A-4). |
| OI-7 | **(Feature 3)** Should a batch summary endpoint be added for the card strip? Currently the card strip issues N parallel `GET /api/dashboard/portfolios/{id}/summary` requests. For 3-5 portfolios this is fine. For 10+ portfolios, a single `GET /api/dashboard/portfolios/summaries?portfolioId=A&portfolioId=B&...` endpoint returning an array would be more efficient. | Solutions Architect | If portfolios remain < 10, no action needed. If the count grows, the batch endpoint avoids N round-trips and allows server-side sorting. Recommend: defer until portfolio count exceeds 10 for any tenant. |
| OI-8 | **(Feature 3)** How should multi-currency portfolios be sorted? The current design sums `|totalValue|` across currencies (e.g. 100k EUR + 50k GBP = 150k). This is an approximation since currencies are not FX-converted. | Product / UI Architect | For EU power trading, most portfolios are single-currency (EUR). The approximation is acceptable for sort order. If FX conversion is needed, it requires a backend service. Recommend: sum of absolute values, documented as an approximation. |
| OI-9 | **(Feature 3)** Should the card strip show a "total across all portfolios" summary card? Some traders want to see the aggregate position across all portfolios. | Product | This would require either a new backend endpoint or client-side aggregation (which is unreliable across currencies). Recommend: defer. The card strip's value is showing individual portfolios at a glance, not aggregating them. |

---

## Appendix A -- State Transition Diagram

```
L2 Period Click
  |
  v
selectedPeriod = { start, end, status }
selectedPositionIds = {}          <- cleared
activatedPositionId = null        <- cleared
selectedDay = null                <- cleared
selectedDayRange = {}             <- cleared
  |
  v
L3 renders with checkbox column
  |
  +--> Checkbox click (toggle) --> selectedPositionIds updated
  |                                activatedPositionId unchanged
  |                                selectedDay cleared (cascade)
  |
  +--> Row body click (activate) --> activatedPositionId = positionId
  |                                  selectedPositionIds unchanged
  |                                  selectedDay cleared
  |
  v
L4 renders with positionIds derived from:
  - selectedPositionIds (if size >= 2) --> netted view
  - [activatedPositionId] (if size <= 1 and activated) --> single view
  - [] (if size == 0 and not activated) --> portfolio view (not rendered)
  |
  +--> Day click --> selectedDay = dayStart, selectedDayRange = { dayStart }
  |
  +--> Shift+Day click --> selectedDayRange = contiguous range
  |
  v
Sub-daily grid renders with dayStart..dayEnd from range
```

## Appendix B -- Migration Notes

### B.1 Backward Compatibility

The `selectedPositionId` field in the current `useDashboardSelection` store is renamed to `activatedPositionId`. All references in `DashboardPage.tsx` and `IntervalDetailPanel.tsx` must be updated. The `setSelectedPosition` action is renamed to `setActivatedPosition`.

This is a breaking change to the store interface. Since the store is internal (not exported to other packages), the migration is a find-and-replace within `pv-ui/src/`.

### B.2 Props Migration

`PositionLedger.selectedPositionId` is replaced by `selectedPositionIds` + `activatedPositionId`. `IntervalDetailPanel.positionId` is replaced by `positionIds: string[]`.

### B.3 Query Hook Migration

`useDailyAggregates`, `useSettledDayDetail`, `useForwardDayDetail` change their `positionId?: string` parameter to `positionIds: string[]`. Callers must be updated. The query key factories change accordingly.

### B.4 API Client Migration

`apiFetch` param type changes from `Record<string, string | number | boolean | undefined>` to `Record<string, string | number | boolean | string[] | undefined>`. This is backward-compatible -- existing callers pass strings, which still match.

### B.5 DashboardFilterBar Migration (Feature 3)

The `PortfolioSelect` component (lines 44-71 in `DashboardFilterBar.tsx`) and its associated `handlePortfolioChange` callback are deleted. The `FALLBACK_PORTFOLIO_IDS` constant and the `useEffect` that auto-selects the first portfolio (lines 248-254) are moved to `PortfolioCardStrip`. The remaining filter bar elements (granularity toggle, date range controls, quick filters) are unchanged. The vertical divider after the portfolio select is also removed.

### B.6 DashboardPage Layout Migration (Feature 3)

The `DashboardPage` layout changes from:

```
Header + Connection Status
DashboardFilterBar (includes portfolio dropdown)
L1: PortfolioSummarySection
L2: RollupGrid
L3: PositionLedger (conditional)
L4: IntervalDetailPanel (conditional)
```

To:

```
Header + Connection Status
PortfolioCardStrip (new -- replaces dropdown)
DashboardFilterBar (no portfolio dropdown)
L1: PortfolioSummarySection (unchanged)
L2: RollupGrid
L3: PositionLedger (conditional)
L4: IntervalDetailPanel (conditional)
```

The `portfolioId` prop on `DashboardPage` continues to come from the route parameter. The card strip reads the route param to determine which card is selected.
