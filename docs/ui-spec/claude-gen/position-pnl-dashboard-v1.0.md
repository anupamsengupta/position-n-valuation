# UI Technical Specification -- Position & PnL Dashboard v1.0

## S1 -- Metadata

| Field | Value |
|-------|-------|
| Author | ui-architect |
| Status | DRAFT |
| Version | 1.0 |
| Date | 2026-08-13 |
| Depends On | `position-pnl-dashboard-v1.0.md` (backend tech spec), `position-pnl-dashboard.functional.md` v3.0 (functional spec), ADR-002 (Forward Mark Compute-on-Demand) |
| Linked Functional Spec | AC-L1-01 through AC-L1-08, AC-L2-01 through AC-L2-07, AC-L3-01 through AC-L3-05, AC-L4-01 through AC-L4-14, AC-TOTAL-01, AC-TOTAL-02 |
| Frontend Project | `pv-ui/` (new -- bootstrapped by this spec) |

This is the first UI feature for the platform. This spec also defines the foundational project structure, design tokens, and shared infrastructure that subsequent features will inherit.

---

## S2 -- Scope

### 2.1 In Scope

1. **Project bootstrap:** Vite 6 + React 19 + TypeScript strict + Tailwind CSS 4 + TanStack Router + TanStack Query v5 + Zustand + Radix/Shadcn primitives. Dark mode from day one.
2. **Dashboard page** at route `/dashboard/:portfolioId` with four progressive-disclosure sections:
   - L1: Portfolio summary cards (top of page, always visible)
   - L2: Rollup cell grid (below L1, always visible for selected portfolio)
   - L3: Position ledger table (appears on L2 row click or as a persistent sub-section)
   - L4: Interval detail panel (appears on L3 row click or L2 day-row click)
3. **Shared infrastructure:** API client layer, tenant context, number formatting utilities, date/timezone utilities, skeleton components, connection status indicator.
4. **Design tokens and primitives:** Color palette (light + dark), typography scale, spacing scale, monospace numeric font, status colors (settled/transition/forward).

### 2.2 Out of Scope

1. Authentication / login UI (auth mechanism TBD per CLAUDE.md).
2. Trade capture forms (separate feature).
3. Real-time push via SSE/WebSocket (backend defers this -- v1.0 is poll-based via TanStack Query `refetchInterval`).
4. Staleness computation (AC-L1-08 deferred in backend spec OI-5). The UI will display `dataAsOf` timestamp but not compute staleness against current curve versions.
5. Cross-currency aggregation.
6. Peak/off-peak toggle (backend currently sets `isPeak = false` always; AC-L2-05 deferred).
7. Portfolio list / portfolio selector page. This spec assumes the user navigates to `/dashboard/:portfolioId` directly or from a future portfolio list.
8. As-Of Toggle for bitemporal views. The backend dashboard endpoints currently read current-knowledge only. The As-Of Toggle infrastructure will be built (Zustand slice, header controls) but will not be functional until backend endpoints support `knowledgeTime` / `businessTime` parameters.
9. Netting toggle for portfolio-scoped L4 views (OQ-4 deferred -- defaults to individual-per-position).

---

## S3 -- Information Architecture

### 3.1 Navigation Location

- **Main nav:** Dashboard is the landing page. Left sidebar nav item: "Position & PnL".
- **URL structure:** `/dashboard/:portfolioId`
- **Search params (URL state):**
  - `rangeStart` -- ISO-8601 date (local CET/CEST), default: first day of current year
  - `rangeEnd` -- ISO-8601 date (local CET/CEST), default: last day of next year
  - `granularity` -- `DAILY | WEEKLY | MONTHLY | YEARLY`, default: `MONTHLY`
  - `periodStart` -- ISO-8601 date, set when L3 is open (selected period start)
  - `periodEnd` -- ISO-8601 date, set when L3 is open (selected period end)
  - `positionId` -- UUID, set when L4 is open for a specific position
  - `day` -- ISO-8601 date, set when L4 day view is open
  - `subGranularity` -- `MIN_15 | MIN_30 | HOURLY`, default: `MIN_15`
- **Entry points:** Main nav click, direct URL with portfolio ID.

### 3.2 Progressive Disclosure Flow

```
L1 Portfolio Cards (always visible)
  |
  v (click portfolio card -- currently single-portfolio, so auto-loaded)
L2 Rollup Grid (always visible below L1)
  |
  v (click rollup row -- sets periodStart/periodEnd in URL)
L3 Position Ledger (slides in below L2, or replaces L2 bottom half)
  |
  v (click position row -- sets positionId + day in URL)
L4 Interval Detail (slides in as a right drawer or bottom panel)
```

Each level deeper adds search params to the URL. Clearing a param collapses that level. The URL is always deep-linkable and shareable.

### 3.3 Page Layout

```
+------------------------------------------------------------------+
| App Shell: Sidebar Nav | Header (tenant, as-of toggle, user)     |
+------------------------------------------------------------------+
| Dashboard Page                                                    |
|                                                                   |
| +--------------------------------------------------------------+ |
| | L1: Portfolio Summary Cards (1-5 cards, one per currency)     | |
| |   [Realized PnL] [Unrealized MtM] [Total Value] [Net MW/MWh]| |
| +--------------------------------------------------------------+ |
|                                                                   |
| +--------------------------------------------------------------+ |
| | L2: Rollup Grid                                               | |
| |   [Granularity Toggle: D | W | M | Y]  [Date Range Picker]  | |
| |   +--------------------------------------------------------+ | |
| |   | Period | Status | Net MW | Net MWh | ... | Total Value | | |
| |   | 2026-01| SETTLED| 12.5   | 9300    | ... | EUR 234,567 | | |
| |   | ...    |        |        |         |     |             | | |
| |   +--------------------------------------------------------+ | |
| +--------------------------------------------------------------+ |
|                                                                   |
| +--------------------------------------------------------------+ |
| | L3: Position Ledger (visible when a period is selected)       | |
| |   Period: 2026-08 (TRANSITION)                                | |
| |   +--------------------------------------------------------+ | |
| |   | Trade | Leg | Status | Settled MW | ... | Unrealized   | | |
| |   | T-7788| L1  | PARTIAL| 12.3       | ... | EUR 45,000   | | |
| |   +--------------------------------------------------------+ | |
| +--------------------------------------------------------------+ |
|                                                                   |
| +--------------------------------------------------------------+ |
| | L4: Interval Detail (visible when a position+day is selected) | |
| |   [Settled | Forward] tabs   [15m | 30m | 60m] toggle        | |
| |   +--------------------------------------------------------+ | |
| |   | Time (CET) | Time (UTC) | MW | MWh | Price | Amount   | | |
| |   | 00:00      | 22:00      | 15 | 3.75| 42.50 | 159.38   | | |
| |   +--------------------------------------------------------+ | |
| +--------------------------------------------------------------+ |
+------------------------------------------------------------------+
```

---

## S4 -- Screens & Layouts

### 4.1 Dashboard Page

**Primary user tasks:**
1. Assess total portfolio value at a glance (L1)
2. Identify which time periods drive value (L2)
3. Drill into specific trades contributing to a period (L3)
4. Inspect interval-level detail for reconciliation or risk analysis (L4)

**Keyboard shortcuts:**
| Key | Action |
|-----|--------|
| `Escape` | Close current drill-down level (L4 -> L3 -> L2) |
| `Arrow Up/Down` | Navigate rows in the active grid |
| `Enter` | Drill into selected row (open next level) |
| `Ctrl+F` | Focus filter input on active grid |
| `G then D/W/M/Y` | Switch granularity (vim-style chord) |

**Empty state:** When `portfolioId` resolves to a portfolio with no rollup data, show a centered empty state: "No position data available for this portfolio. Trades must be captured and settlement/rollup pipelines must complete before data appears here."

**Loading state:** Skeleton placeholders matching the layout shape. L1 shows 1-3 skeleton cards. L2 shows 12 skeleton rows at 24px height. L3 and L4 are not shown until their parent is loaded and a selection is made.

**Error state:** Error boundary at the page level catches unrecoverable errors. Per-section error boundaries show inline error messages with a "Retry" button. The error message includes the HTTP status and a correlation ID if available from the response.

---

## S5 -- Components

### 5.1 Component Tree

```
<DashboardPage>                              -- route component
  <DashboardHeader>                          -- date range picker, granularity toggle
    <DateRangePicker />                      -- rangeStart/rangeEnd
    <GranularityToggle />                    -- DAILY|WEEKLY|MONTHLY|YEARLY
  </DashboardHeader>
  <PortfolioSummarySection>                  -- L1
    <PortfolioCard />                        -- one per currency
      <KpiTile label value trend />          -- reusable KPI display
  </PortfolioSummarySection>
  <RollupGrid>                               -- L2
    <RollupGridToolbar />                    -- column show/hide, export
    <VirtualizedTable />                     -- TanStack Table + react-virtual
      <RollupRow />                          -- per-row rendering
        <NumericCell />                      -- right-aligned, formatted
        <StatusBadge />                      -- SETTLED|TRANSITION|FORWARD
  </RollupGrid>
  <PositionLedger>                           -- L3 (conditional)
    <PositionLedgerToolbar />                -- period label, close button
    <VirtualizedTable />
      <PositionRow />
        <NumericCell />
        <StatusBadge />
  </PositionLedger>
  <IntervalDetailPanel>                      -- L4 (conditional)
    <IntervalDetailHeader />                 -- tabs (settled/forward), sub-granularity toggle
    <SettledDayGrid />                       -- TanStack Table for S5a (Settlement Cells) data
    <ForwardDayGrid />                       -- TanStack Table for ForwardMarkService/S6b (Trade Interval Cache) data
    <MonthViewGrid />                        -- daily aggregate rows
  </IntervalDetailPanel>
</DashboardPage>
```

### 5.2 Shared/Primitive Components

#### `NumericCell`

Renders a right-aligned, monospace-font number with domain-appropriate formatting.

```typescript
interface NumericCellProps {
  value: number | null | undefined;
  precision: 'PRICE' | 'MONETARY' | 'MW' | 'MWH' | 'PERCENTAGE';
  currency?: string;            // ISO 4217 code, shown as suffix for MONETARY
  colorNegative?: boolean;      // default true -- red text or parentheses for negatives
  showSign?: boolean;           // default false -- explicit + for positives
  nullDisplay?: string;         // default '--'
}
```

Precision mapping (matches backend `NumericPrecision`):
| Precision | `minimumFractionDigits` | `maximumFractionDigits` | Notes |
|-----------|------------------------|------------------------|-------|
| `PRICE` | 2 | 8 | Trailing zeros trimmed to min 2 |
| `MONETARY` | 2 | 4 | Trailing zeros trimmed to min 2 |
| `MW` | 2 | 4 | Suffix "MW" |
| `MWH` | 2 | 4 | Suffix "MWh" |
| `PERCENTAGE` | 2 | 2 | Suffix "%" |

Accessibility: Uses `aria-label` with the full unformatted value for screen readers. Negative values announced as "negative [value]".

Storybook stories: `Default`, `Negative`, `Null`, `HighPrecision`, `WithCurrency`, `Zero`.

#### `StatusBadge`

```typescript
interface StatusBadgeProps {
  status: 'SETTLED' | 'TRANSITION' | 'PARTIAL' | 'FORWARD' | 'TODAY';
  size?: 'sm' | 'md';
}
```

Visual: Pill-shaped badge with background color and text.
- SETTLED: green background, "Settled" text, checkmark icon
- TRANSITION/PARTIAL/TODAY: amber background, "Transition"/"Partial"/"Today" text, clock icon
- FORWARD: blue background, "Forward" text, arrow-right icon

Colors must meet 4.5:1 contrast ratio in both light and dark themes.

#### `KpiTile`

```typescript
interface KpiTileProps {
  label: string;                // i18n key
  value: number | null;
  precision: NumericCellProps['precision'];
  currency?: string;
  secondaryLabel?: string;      // e.g., "vs. prior month"
  secondaryValue?: number | null;
  trend?: 'up' | 'down' | 'flat' | null;
}
```

Layout: Label top, large value center, secondary value bottom-right with trend arrow.

#### `VirtualizedTable`

Wrapper around TanStack Table + `@tanstack/react-virtual`.

```typescript
interface VirtualizedTableProps<TData> {
  data: TData[];
  columns: ColumnDef<TData>[];
  rowHeight?: number;           // default 24
  overscan?: number;            // default 5
  stickyHeader?: boolean;       // default true
  stickyFirstColumn?: boolean;  // default false
  onRowClick?: (row: TData) => void;
  selectedRowId?: string;
  getRowId: (row: TData) => string;
  emptyMessage?: string;
  isLoading?: boolean;
  skeletonRowCount?: number;    // number of skeleton rows to show while loading
}
```

Features:
- Column resizing via drag handles
- Column reordering via drag-and-drop (future -- v1.0 fixed order)
- Column show/hide via right-click context menu on header
- Keyboard navigation: Arrow keys move selection, Enter triggers `onRowClick`
- Zebra striping via CSS `nth-child` (toggleable via user preference in Zustand)
- 24px row height, `text-xs` font, monospace for numeric columns

Accessibility:
- `role="grid"` on container, `role="row"` on rows, `role="gridcell"` on cells
- `aria-selected` on the focused/selected row
- `aria-sort` on sortable column headers
- Focus management: table traps arrow key focus within the grid; Tab exits the grid

#### `DateRangePicker`

```typescript
interface DateRangePickerProps {
  startDate: string;            // ISO-8601 local date (YYYY-MM-DD)
  endDate: string;
  onChange: (start: string, end: string) => void;
  timezone: string;             // default 'Europe/Berlin'
}
```

Converts local CET/CEST dates to UTC `Instant` values before passing to API calls. Uses `date-fns-tz` for conversion, correctly handling DST boundaries.

#### `GranularityToggle`

```typescript
interface GranularityToggleProps {
  value: TimeGranularity;
  onChange: (g: TimeGranularity) => void;
  options?: TimeGranularity[];  // default: all four
}
```

Rendered as a segmented control (Radix `ToggleGroup`). Keyboard: arrow keys cycle options. `aria-label="Time granularity"`.

#### `SubGranularityToggle`

Same pattern as `GranularityToggle` but for sub-daily: `MIN_15 | MIN_30 | HOURLY`.

#### `SkeletonRow`

```typescript
interface SkeletonRowProps {
  columnWidths: number[];       // percentage widths matching the real columns
  height?: number;              // default 24
}
```

Renders animated placeholder bars at the correct column widths. Used by `VirtualizedTable` when `isLoading = true`.

#### `ConnectionStatus`

Displays a colored dot in the app header indicating data freshness.
- Green dot + "Live" when last successful fetch < 30s ago
- Amber dot + "Refreshing..." during active fetch
- Red dot + "Stale (updated Xs ago)" when last fetch > 60s ago or errored

For v1.0 (poll-based), this reflects TanStack Query's `isFetching` and `dataUpdatedAt` states.

---

## S6 -- State Management

### 6.1 Server State (TanStack Query)

All server data flows through TanStack Query. No `useEffect` fetching.

#### Query Keys

```typescript
// Key factory pattern
const dashboardKeys = {
  all: ['dashboard'] as const,

  portfolioSummary: (tenantId: string, portfolioId: string, rangeStart: string, rangeEnd: string, granularity: TimeGranularity) =>
    [...dashboardKeys.all, 'summary', tenantId, portfolioId, rangeStart, rangeEnd, granularity] as const,

  rollupGrid: (tenantId: string, portfolioId: string, rangeStart: string, rangeEnd: string, granularity: TimeGranularity) =>
    [...dashboardKeys.all, 'rollups', tenantId, portfolioId, rangeStart, rangeEnd, granularity] as const,

  positionContributions: (tenantId: string, portfolioId: string, periodStart: string, periodEnd: string) =>
    [...dashboardKeys.all, 'positions', tenantId, portfolioId, periodStart, periodEnd] as const,

  dailyAggregates: (tenantId: string, portfolioId: string, monthStart: string, monthEnd: string, positionId?: string) =>
    [...dashboardKeys.all, 'daily', tenantId, portfolioId, monthStart, monthEnd, positionId ?? 'all'] as const,

  settledDay: (tenantId: string, portfolioId: string, dayStart: string, dayEnd: string, granularity: SubDailyGranularity, positionId?: string) =>
    [...dashboardKeys.all, 'settled-day', tenantId, portfolioId, dayStart, dayEnd, granularity, positionId ?? 'all'] as const,

  forwardDay: (tenantId: string, portfolioId: string, dayStart: string, dayEnd: string, granularity: SubDailyGranularity, positionId?: string) =>
    [...dashboardKeys.all, 'forward-day', tenantId, portfolioId, dayStart, dayEnd, granularity, positionId ?? 'all'] as const,
} as const;
```

#### Staleness and GC Configuration

| Query | `staleTime` | `gcTime` | `refetchInterval` | Rationale |
|-------|------------|---------|-------------------|-----------|
| `portfolioSummary` | 30s | 5min | 30s | Summary data changes on rollup refresh events |
| `rollupGrid` | 30s | 5min | 30s | Same cadence as summary |
| `positionContributions` | 60s | 5min | 60s | Position data changes less frequently |
| `dailyAggregates` | 30s | 5min | 30s | Mix of settled (stable) and forward (variable) |
| `settledDay` | 120s | 5min | disabled | Settled data is stable; manual refresh via button |
| `forwardDay` | 15s | 5min | 15s | Forward marks change with curve ticks |

All `refetchInterval` values are the poll-based fallback for v1.0. When SSE is added in a future version, these intervals will be disabled while the SSE connection is active, and re-enabled on disconnect (per the real-time subscription architecture in the system prompt).

### 6.2 Client State (Zustand)

#### `useTenantStore`

```typescript
interface TenantState {
  tenantId: string;
  tenantName: string;
  setTenant: (id: string, name: string) => void;
}
```

Populated from auth context (JWT claim) or a tenant selector for admin roles. Never defaults to a hardcoded value. The simulator will provide a tenant ID via environment config (`VITE_DEFAULT_TENANT_ID`), but the store does not import it directly -- the app shell reads the env var and calls `setTenant` on mount.

#### `useAsOfClock`

```typescript
interface AsOfClockState {
  knowledgeTime: string | null;   // ISO-8601 Instant, null = 'now'
  businessTime: string | null;    // ISO-8601 Instant, null = 'now'
  isNonCurrent: boolean;          // derived: true if either is non-null
  setKnowledgeTime: (t: string | null) => void;
  setBusinessTime: (t: string | null) => void;
  resetToNow: () => void;
}
```

Threaded into every TanStack Query key when non-null. When either dimension changes, all affected queries are invalidated. Visual affordance: clock icon in header turns amber when `isNonCurrent` is true.

**Note:** For v1.0, the backend dashboard endpoints do not accept as-of parameters. The store is built and the UI controls are present but disabled with a tooltip: "As-of viewing is not yet available for this dashboard." This prevents a future redesign.

#### `useUserPreferences`

```typescript
interface UserPreferencesState {
  timezone: string;               // default 'Europe/Berlin'
  negativeNumberStyle: 'red' | 'parentheses' | 'both';
  zebraStriping: boolean;
  theme: 'light' | 'dark' | 'system';
  setPreference: <K extends keyof UserPreferencesState>(key: K, value: UserPreferencesState[K]) => void;
}
```

Persisted to `localStorage` under a non-tenant-scoped key (these are user preferences, not tenant data).

#### `useDashboardSelection`

```typescript
interface DashboardSelectionState {
  selectedPeriod: { start: string; end: string; status: PeriodStatus } | null;
  selectedPositionId: string | null;
  selectedDay: string | null;           // ISO-8601 local date
  selectedDayStatus: 'SETTLED' | 'TODAY' | 'FORWARD' | null;
  setSelectedPeriod: (period: { start: string; end: string; status: PeriodStatus } | null) => void;
  setSelectedPosition: (id: string | null) => void;
  setSelectedDay: (day: string | null, status: 'SETTLED' | 'TODAY' | 'FORWARD' | null) => void;
  clearAll: () => void;
}
```

This state is mirrored to URL search params via TanStack Router's search param schema. The Zustand store is the source of truth; URL params are synced on change for deep-linking.

### 6.3 URL State (TanStack Router)

```typescript
// Route search param schema
const dashboardSearchSchema = z.object({
  rangeStart: z.string().optional(),
  rangeEnd: z.string().optional(),
  granularity: z.enum(['DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY']).optional().default('MONTHLY'),
  periodStart: z.string().optional(),
  periodEnd: z.string().optional(),
  positionId: z.string().uuid().optional(),
  day: z.string().optional(),
  subGranularity: z.enum(['MIN_15', 'MIN_30', 'HOURLY']).optional().default('MIN_15'),
});
```

URL is the canonical source for navigation state. When the user shares a URL, the recipient sees the same drill-down state.

---

## S7 -- Data Contract

### 7.1 API Client

All API calls go through a typed client using `fetch` wrapped by TanStack Query. The client:
- Reads `tenantId` from `useTenantStore` and injects it as a query parameter on every request.
- Sets `Content-Type: application/json` and `Accept: application/json`.
- In production, will send a `Bearer` token via `Authorization` header (auth mechanism TBD).
- Handles HTTP errors: 4xx mapped to typed error responses, 5xx trigger error boundaries.

Base URL configured via `VITE_API_BASE_URL` environment variable.

### 7.2 Endpoints Consumed

#### A.1 -- Portfolio Summary (L1)

```
GET /api/dashboard/portfolios/{portfolioId}/summary
  ?tenantId={tenantId}
  &rangeStart={ISO-8601 Instant}
  &rangeEnd={ISO-8601 Instant}
  &granularity=MONTHLY
```

**Response:** `ApiResponse<PortfolioSummaryDto[]>`

```typescript
interface PortfolioSummaryDto {
  portfolioId: string;
  currency: string;
  realizedPnl: string;           // BigDecimal as string to preserve precision
  unrealizedMtm: string;
  totalPortfolioValue: string;
  settledNetMw: string;
  settledNetMwh: string;
  forwardNetMw: string;
  forwardNetMwh: string;
  dataAsOf: string;              // ISO-8601 Instant
}
```

**Precision note:** All numeric values are transmitted as strings from the backend to preserve BigDecimal precision. The UI parses them with `Number()` for display formatting via `Intl.NumberFormat`. For intermediate calculations (if any), use `Decimal.js` at the boundary. For display-only purposes, `Number()` is acceptable since the values fit within IEEE 754 double precision for the expected magnitude (portfolio values up to ~10^12 with 4 decimal places).

#### A.2 -- Rollup Grid (L2)

```
GET /api/dashboard/portfolios/{portfolioId}/rollups
  ?tenantId={tenantId}
  &rangeStart={ISO-8601 Instant}
  &rangeEnd={ISO-8601 Instant}
  &granularity=MONTHLY
```

**Response:** `ApiResponse<RollupCellDto[]>`

```typescript
interface RollupCellDto {
  id: string;                     // UUID
  tenantId: string;
  portfolioId: string;
  deliveryPointId: string;
  intervalStart: string;          // ISO-8601 Instant (period start)
  intervalEnd: string;            // ISO-8601 Instant (period end)
  granularity: TimeGranularity;
  isPeak: boolean;
  netMw: string;
  netMwh: string;
  price: string | null;
  marketPrice: string | null;
  settledValue: string;
  marketValue: string;
  pnl: string;
  forwardMarkValue: string;
  currency: string;
  versionHash: string;
}
```

**Derived field (computed in UI):** `totalValue = settledValue + forwardMarkValue` (for display; not a server field). Also: `periodStatus` derived by comparing `intervalEnd` against `Instant.now()`: SETTLED if `intervalEnd < now`, FORWARD if `intervalStart > now`, TRANSITION otherwise.

#### A.3 -- Position Contributions (L3)

```
GET /api/dashboard/portfolios/{portfolioId}/positions
  ?tenantId={tenantId}
  &periodStart={ISO-8601 Instant}
  &periodEnd={ISO-8601 Instant}
  &offset=0
  &limit=50
```

**Response:** `ApiResponse<PositionContributionDto[]>`

```typescript
interface PositionContributionDto {
  positionId: string;             // UUID
  tradeId: string;
  tradeLegId: string;
  tradeVersion: number;
  deliveryStart: string;          // ISO-8601 Instant
  deliveryEnd: string;
  quantity: string;
  volumeUnit: string;
  deliveryPointId: string;
  deliveryStatus: 'SETTLED' | 'PARTIAL' | 'FORWARD';
  settledMw: string | null;
  settledMwh: string | null;
  avgPrice: string | null;
  settledValue: string | null;
  marketValue: string | null;
  realizedPnl: string | null;
  forwardMw: string | null;
  forwardMwh: string | null;
  forwardMarkValue: string | null;
  unrealizedMtm: string | null;
  currency: string;
}
```

**Pagination:** Offset-based. Default page size 50, max 200. The UI uses TanStack Query's `keepPreviousData` to avoid flickering on page changes. If the total count exceeds 200, a warning banner is shown: "Showing first 200 positions. Contact support if your portfolio exceeds this limit."

#### A.4 -- Daily Aggregates (L4 Month View)

```
GET /api/dashboard/portfolios/{portfolioId}/daily
  ?tenantId={tenantId}
  &monthStart={ISO-8601 Instant}
  &monthEnd={ISO-8601 Instant}
  &positionId={UUID}              // optional
  &timezone=Europe/Berlin
```

**Response:** `ApiResponse<DailyAggregateDto[]>`

```typescript
interface DailyAggregateDto {
  dayStart: string;               // ISO-8601 Instant (UTC boundary of CET/CEST day)
  dayEnd: string;
  dayStatus: 'SETTLED' | 'TODAY' | 'FORWARD';
  intervalCount: number;          // 92, 96, or 100

  // Settled fields (null for FORWARD days)
  settledMw: string | null;
  settledMwh: string | null;
  avgPrice: string | null;
  settledValue: string | null;
  marketValue: string | null;
  realizedPnl: string | null;

  // Forward fields (null for SETTLED days)
  forwardMw: string | null;
  forwardMwh: string | null;
  curvePrice: string | null;
  forwardMarkValue: string | null;

  currency: string;
}
```

#### A.5 -- Settled Day Detail (L4)

```
GET /api/dashboard/settlements/day
  ?tenantId={tenantId}
  &portfolioId={portfolioId}
  &positionId={UUID}              // optional
  &dayStart={ISO-8601 Instant}
  &dayEnd={ISO-8601 Instant}
  &granularity=MIN_15
```

**Response:** `ApiResponse<SettlementCellDto[]>`

```typescript
interface SettlementCellDto {
  id: string;
  tenantId: string;
  positionId: string;
  intervalStart: string;
  intervalEnd: string;
  volumeMw: string;
  volumeMwh: string;
  price: string;
  amount: string;
  marketPrice: string;
  marketAmount: string;
  pnl: string;
  currency: string;
  computedAt: string;
}
```

#### A.6 -- Forward Day Detail (L4)

```
GET /api/dashboard/forward/day
  ?tenantId={tenantId}
  &portfolioId={portfolioId}
  &positionId={UUID}              // optional
  &dayStart={ISO-8601 Instant}
  &dayEnd={ISO-8601 Instant}
  &granularity=MIN_15
```

**Response:** `ApiResponse<ForwardIntervalDetailDto[]>`

```typescript
interface ForwardIntervalDetailDto {
  intervalStart: string;
  intervalEnd: string;
  positionId: string | null;
  tradeLegId: string | null;
  resolvedQty: string;          // MW from S6b (Trade Interval Cache)
  resolvedEnergy: string;       // MWh from S6b (Trade Interval Cache)
  multiplier: string;
  seriesKey: string;
  evaluatedPrice: string | null; // from ForwardMarkService (S4 Forward Curves + shaping)
  markValue: string | null;      // evaluatedPrice × resolvedEnergy
  curveId: string | null;        // curve used for price evaluation
  curveVersion: number | null;   // curve version at computation time
  currency: string | null;
}
```

### 7.3 API Response Wrapper

```typescript
interface ApiResponse<T> {
  data: T;
  meta?: {
    totalCount?: number;
    offset?: number;
    limit?: number;
  };
  error?: {
    code: string;
    message: string;
    correlationId?: string;
  };
}
```

### 7.4 Optimistic Updates

Not applicable for v1.0. The dashboard is read-only. No mutations.

---

## S8 -- Real-time & Bitemporal

### 8.1 Real-time (v1.0 -- Poll-based)

No SSE or WebSocket in v1.0 (backend defers per OQ-7). All data freshness is achieved through TanStack Query `refetchInterval` as specified in S6.1.

The `ConnectionStatus` component in the header tracks the most recent successful fetch timestamp across all active queries. It provides visual feedback so the user knows data is being refreshed.

**Future SSE integration point:** The `useLiveSubscription` hook described in the system prompt will be implemented when the backend adds SSE endpoints. It will push updates into the TanStack Query cache via `queryClient.setQueryData()` using the same query keys defined in S6.1. Components will not need changes -- they already consume data from the query cache.

### 8.2 Bitemporal (v1.0 -- Current Knowledge Only)

The As-Of Toggle UI is built but disabled. The `useAsOfClock` Zustand slice is wired into the query key factory (S6.1) so that when backend support lands, enabling the toggle requires:
1. Backend endpoints accept `knowledgeTime` and `businessTime` parameters.
2. Remove the `disabled` prop from the As-Of Toggle component.
3. The query keys already include the as-of dimensions, so cache invalidation works automatically.

**Note on forward mark as-of (ADR-002):** Forward marks are computed on demand by ForwardMarkService from current S4 (Forward Curves) × S6b (Trade Interval Cache). Historical forward MtM is available via S5c (EOD Mark Snapshot) daily batch at the grain of `(position × delivery-month × business-date)`. When the as-of toggle is enabled, forward mark as-of queries should be routed to S5c snapshots rather than recomputing from historical S4/S6b versions. This routing logic is a backend concern.

### 8.3 Staleness Indicators

For v1.0, the UI displays `dataAsOf` from the `PortfolioSummaryDto` response. This is the `max(computedAt)` across contributing cells -- it tells the user when the data was last computed, not whether it is stale relative to current market data.

The full staleness detection (AC-L1-08) is deferred per backend OI-5. The UI reserves space in the L1 card design for a staleness warning badge. The badge is hidden until the staleness endpoint exists.

---

## S8a -- Performance

### Pagination Strategy

| Grid | Strategy | Page Size | Server vs Client |
|------|----------|-----------|-----------------|
| L2 Rollup Grid | No pagination (max ~104 rows for WEEKLY) | N/A | Server returns all |
| L3 Position Ledger | Offset-based | 50 (max 200) | Server-side |
| L4 Month View | No pagination (max 31 rows) | N/A | Server returns all |
| L4 Day View (settled) | No pagination (max 100 rows) | N/A | Server returns all |
| L4 Day View (forward) | No pagination for single position; offset for portfolio scope | 100 (max 500) | Server-side |

### Virtualization

All grids use `@tanstack/react-virtual` via the `VirtualizedTable` component:
- Row height: 24px
- Overscan: 5 rows
- DOM never contains more than ~50 row elements regardless of dataset size
- Header row is sticky (CSS `position: sticky`)
- First column (trade ID / interval time) is sticky on horizontal scroll

For L2 and L4 month view (max ~100 rows), virtualization is still applied for consistency, even though the dataset fits in the viewport. The performance cost of virtualizing small lists is negligible, and it prevents regressions if data volume grows.

### Lazy Loading

- L3 data is fetched only when a period is selected in L2 (progressive disclosure).
- L4 data is fetched only when a position/day is selected in L3 (or a day-row is clicked in the month view).
- No prefetching on hover for v1.0. This can be added as a performance enhancement later.

### Bundle Splitting

```
Entry chunk:
  - React, TanStack Router, Zustand, Tailwind runtime
  - App shell, sidebar, header
  - Design tokens, primitives (Button, Input, NumericCell)

Lazy chunks:
  - DashboardPage (includes L1 + L2 components)
  - PositionLedger (L3 -- loaded on first period selection)
  - IntervalDetailPanel (L4 -- loaded on first day/position selection)
  - DateRangePicker (Radix Popover + calendar, loaded on first click)
```

TanStack Table is included in the DashboardPage chunk since L2 always renders. `@tanstack/react-virtual` is bundled with TanStack Table. No chart libraries are needed for v1.0.

### Memoization

- `useMemo` for `periodStatus` derivation on L2 rows (comparing `intervalEnd` against `now`). Re-derives only when `rollupGrid` query data changes.
- `useMemo` for `totalValue` computation on L2 rows (`settledValue + forwardMarkValue`).
- Column definitions for all grids are defined outside the component (module-level constants) to avoid re-creation on every render.
- `useCallback` for row click handlers to maintain stable references for `VirtualizedTable`.

---

## S8b -- DST Handling

### Interval Grids (L4 Day View)

**Normal day (96 intervals at MIN_15):** Grid renders 96 rows. Time column shows CET/CEST local time (e.g., `00:00`, `00:15`, ..., `23:45`) alongside UTC in a second column or tooltip.

**Spring-forward day (92 intervals at MIN_15):**
- Grid renders 92 rows. The hour 02:00-03:00 CET does not exist.
- Rows jump from `01:45 CET` to `03:00 CET`.
- A subtle banner above the grid states: "23-hour delivery day (DST spring-forward). The hour 02:00-03:00 CET does not exist."
- At MIN_30: 46 rows. At HOURLY: 23 rows.
- The `intervalCount` from `DailyAggregateDto` (92) is used to validate the expected row count.

**Fall-back day (100 intervals at MIN_15):**
- Grid renders 100 rows.
- The duplicate hour 02:00-03:00 appears twice.
- First occurrence labeled: `02:00 (CEST)`, `02:15 (CEST)`, `02:30 (CEST)`, `02:45 (CEST)`
- Second occurrence labeled: `02:00 (CET)`, `02:15 (CET)`, `02:30 (CET)`, `02:45 (CET)`
- A subtle banner states: "25-hour delivery day (DST fall-back). The hour 02:00-03:00 occurs twice."
- At MIN_30: 50 rows. At HOURLY: 25 rows.
- The UI distinguishes the two occurrences by checking the UTC offset: if the interval's UTC representation falls in the CEST range, label as CEST; otherwise CET. Since all timestamps from the API are UTC, the conversion via `date-fns-tz` `formatInTimeZone` will naturally produce the correct offset annotation.

### Time Display Format

L4 interval detail shows both local and UTC time:

| Column 1 (Local) | Column 2 (UTC) | Notes |
|------------------|----------------|-------|
| `00:00 CET` | `23:00 UTC` | Winter |
| `00:00 CEST` | `22:00 UTC` | Summer |
| `02:00 (CEST)` | `00:00 UTC` | Fall-back, first occurrence |
| `02:00 (CET)` | `01:00 UTC` | Fall-back, second occurrence |

Format function:

```typescript
function formatIntervalTime(utcInstant: string, timezone: string): { local: string; utc: string; isDstAmbiguous: boolean } {
  // Uses date-fns-tz formatInTimeZone
  // Returns { local: "02:00 (CEST)", utc: "00:00 UTC", isDstAmbiguous: true }
}
```

### Date Range Picker DST Conversion

When the user selects a date range in the `DateRangePicker`, the component converts local CET/CEST dates to UTC boundaries:

```typescript
function localDateToUtcBoundary(localDate: string, timezone: string): string {
  // '2026-08-01' in 'Europe/Berlin' (CEST) -> '2026-07-31T22:00:00Z'
  // '2026-01-15' in 'Europe/Berlin' (CET) -> '2026-01-14T23:00:00Z'
  // Uses: zonedTimeToUtc(startOfDay(parse(localDate)), timezone)
}
```

This function is used for all API calls that accept `rangeStart`, `rangeEnd`, `dayStart`, `dayEnd`, `monthStart`, `monthEnd` parameters.

### L4 Month View -- Daily Rows

Each daily row in the month view shows the `intervalCount` from the `DailyAggregateDto`. For DST transition days, a tooltip explains: "92 intervals (DST spring-forward)" or "100 intervals (DST fall-back)". Normal days show "96 intervals" without special annotation.

---

## S9 -- Accessibility

### 9.1 Keyboard Flow

**Tab order (page level):**
1. Sidebar nav
2. Header controls (tenant display, as-of toggle, user menu)
3. Dashboard header (date range picker, granularity toggle)
4. L1 portfolio cards (each card is focusable)
5. L2 rollup grid (grid receives focus as a composite widget)
6. L3 position ledger (if visible)
7. L4 interval detail (if visible)

**Within grids:**
- Arrow Up/Down: move row focus
- Arrow Left/Right: move cell focus (for grids with many columns)
- Enter: activate row (drill down)
- Escape: exit grid focus (move to previous landmark)
- Home/End: jump to first/last row
- Page Up/Page Down: jump by viewport height

**Progressive disclosure:**
- When L3 opens (period selected in L2), focus moves to the L3 section heading.
- When L4 opens (position/day selected), focus moves to the L4 section heading.
- When Escape closes a level, focus returns to the previously selected row in the parent level.

### 9.2 ARIA Landmarks and Labels

```html
<nav aria-label="Main navigation">...</nav>
<header role="banner">...</header>
<main>
  <section aria-label="Portfolio Summary">
    <div role="list" aria-label="Portfolio cards">
      <div role="listitem" aria-label="EUR summary">...</div>
    </div>
  </section>
  <section aria-label="Rollup Grid">
    <div role="grid" aria-label="Period rollup data" aria-rowcount={totalRows}>
      ...
    </div>
  </section>
  <section aria-label="Position Ledger" aria-expanded={isL3Open}>
    ...
  </section>
  <section aria-label="Interval Detail" aria-expanded={isL4Open}>
    ...
  </section>
</main>
```

### 9.3 Screen Reader Considerations

- `NumericCell` uses `aria-label` with the full formatted value including units: "Negative 12,345.67 EUR".
- `StatusBadge` uses `aria-label`: "Delivery status: Settled".
- Trend arrows in `KpiTile` use `aria-label`: "Trend: up" (never color alone).
- DST banners use `role="status"` for live announcement.
- Loading skeletons use `aria-busy="true"` on the parent section.
- Empty states use `role="status"`.

### 9.4 Contrast Verification

All color pairs must be verified in both light and dark themes:

| Element | Light Theme | Dark Theme | Required Ratio |
|---------|-------------|------------|----------------|
| Body text on background | `#1a1a2e` on `#ffffff` | `#e0e0e0` on `#0f0f23` | 4.5:1 (AA) |
| Numeric text (monospace) | `#1a1a2e` on `#ffffff` | `#d4d4d8` on `#18181b` | 4.5:1 (AA) |
| Negative numbers (red) | `#dc2626` on `#ffffff` | `#f87171` on `#18181b` | 4.5:1 (AA) |
| Status badge text | White on status color | White on status color | 4.5:1 (AA) |
| Focus ring | `#2563eb` (3px outline) | `#60a5fa` (3px outline) | 3:1 (AA for UI) |

---

## S10 -- Testing

### 10.1 Unit Tests (Vitest + React Testing Library)

**Components to test:**

| Component | Test File | Key Behaviors |
|-----------|----------|---------------|
| `NumericCell` | `NumericCell.test.tsx` | Formats PRICE/MONETARY/MW/MWH correctly, handles null, handles negatives (red text, parentheses), applies correct precision |
| `StatusBadge` | `StatusBadge.test.tsx` | Renders correct label and color for each status, meets contrast |
| `KpiTile` | `KpiTile.test.tsx` | Renders label, value, trend arrow; handles null value |
| `VirtualizedTable` | `VirtualizedTable.test.tsx` | Renders rows, handles empty state, keyboard navigation (arrow keys, Enter), skeleton loading state |
| `GranularityToggle` | `GranularityToggle.test.tsx` | Renders options, calls onChange, keyboard arrow navigation |
| `DateRangePicker` | `DateRangePicker.test.tsx` | Converts dates correctly including DST boundaries, validates range |
| `PortfolioCard` | `PortfolioCard.test.tsx` | Renders all KPIs, handles multi-currency, handles empty portfolio |
| `RollupGrid` | `RollupGrid.test.tsx` | Renders rows with correct status derivation, click triggers drill-down, granularity change re-fetches |
| `PositionLedger` | `PositionLedger.test.tsx` | Renders position rows, pagination, status badges |
| `IntervalDetailPanel` | `IntervalDetailPanel.test.tsx` | Renders settled vs forward tabs, sub-granularity toggle, DST day handling |

**Utility functions to test:**

| Utility | Test File | Key Behaviors |
|---------|----------|---------------|
| `formatIntervalTime` | `dateUtils.test.ts` | Normal day, spring-forward (skips 02:00), fall-back (labels CEST/CET), UTC conversion |
| `localDateToUtcBoundary` | `dateUtils.test.ts` | CET winter, CEST summer, spring-forward boundary, fall-back boundary |
| `derivePeriodStatus` | `statusUtils.test.ts` | SETTLED/TRANSITION/FORWARD based on interval dates vs now |
| `formatNumber` | `numberUtils.test.ts` | All precision modes, negatives, nulls, currency suffixes |
| `dashboardKeys` | `queryKeys.test.ts` | Key uniqueness, tenant isolation in keys |

### 10.2 Storybook Stories

| Component | Stories |
|-----------|---------|
| `NumericCell` | Default, Negative, Null, HighPrecisionPrice, WithCurrency, Zero, LargeNumber |
| `StatusBadge` | Settled, Transition, Partial, Forward, Today, SmallSize |
| `KpiTile` | WithValue, WithTrend, NullValue, WithSecondary |
| `PortfolioCard` | SingleCurrency, MultiCurrency, EmptyPortfolio, ForwardOnly, SettledOnly |
| `RollupGrid` | WithData, Loading, Empty, MixedStatus, WeeklyGranularity |
| `PositionLedger` | WithPositions, Loading, Empty, MixedDeliveryStatus, Paginated |
| `IntervalDetailPanel` | SettledDay, ForwardDay, DstSpringForward, DstFallBack, NoMarksAvailable, NoVolumeAvailable |
| `VirtualizedTable` | SmallDataset, LargeDataset, Loading, Empty, WithSelection |

### 10.3 E2E Tests (Playwright)

**User journeys:**

1. **Full drill-down flow:** Navigate to `/dashboard/WIND_DE` -> verify L1 cards render -> click L2 row -> verify L3 position ledger appears -> click L3 row -> verify L4 interval detail appears -> press Escape -> verify L4 closes -> press Escape -> verify L3 closes.

2. **Granularity switching:** On L2, switch from MONTHLY to WEEKLY -> verify row count changes -> switch to DAILY -> verify row count changes -> verify data freshness indicator updates.

3. **URL deep-linking:** Navigate directly to `/dashboard/WIND_DE?periodStart=2026-08-01&periodEnd=2026-08-31&positionId=abc-123&day=2026-08-10&subGranularity=HOURLY` -> verify all four levels are visible with correct data.

4. **Keyboard navigation:** Tab to L2 grid -> arrow down to second row -> Enter -> verify L3 opens -> Tab to L3 grid -> arrow down -> Enter -> verify L4 opens.

5. **Empty states:** Navigate to `/dashboard/EMPTY_PORTFOLIO` -> verify empty state message renders -> verify no console errors.

6. **DST spring-forward day:** Navigate to L4 day view for a spring-forward date -> verify 92 rows at MIN_15 -> verify banner about 23-hour day -> verify no row for 02:00-03:00 CET.

7. **DST fall-back day:** Navigate to L4 day view for a fall-back date -> verify 100 rows at MIN_15 -> verify duplicate hour labeled with CEST/CET.

---

## S11 -- Type Definitions

### 11.1 Domain Types (shared)

```typescript
// Enums matching backend
type TimeGranularity = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'YEARLY';
type SubDailyGranularity = 'MIN_15' | 'MIN_30' | 'HOURLY';
type PeriodStatus = 'SETTLED' | 'TRANSITION' | 'FORWARD';
type DeliveryStatus = 'SETTLED' | 'PARTIAL' | 'FORWARD';
type DayStatus = 'SETTLED' | 'TODAY' | 'FORWARD';
```

### 11.2 Zod Schemas (API boundary validation)

```typescript
import { z } from 'zod';

const bigDecimalString = z.string().refine(
  (val) => !isNaN(Number(val)),
  { message: 'Must be a valid decimal string' }
);

const instantString = z.string().datetime();

export const portfolioSummarySchema = z.object({
  portfolioId: z.string(),
  currency: z.string().length(3),
  realizedPnl: bigDecimalString,
  unrealizedMtm: bigDecimalString,
  totalPortfolioValue: bigDecimalString,
  settledNetMw: bigDecimalString,
  settledNetMwh: bigDecimalString,
  forwardNetMw: bigDecimalString,
  forwardNetMwh: bigDecimalString,
  dataAsOf: instantString,
});

export const rollupCellSchema = z.object({
  id: z.string().uuid(),
  tenantId: z.string(),
  portfolioId: z.string(),
  deliveryPointId: z.string(),
  intervalStart: instantString,
  intervalEnd: instantString,
  granularity: z.enum(['DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY']),
  isPeak: z.boolean(),
  netMw: bigDecimalString,
  netMwh: bigDecimalString,
  price: bigDecimalString.nullable(),
  marketPrice: bigDecimalString.nullable(),
  settledValue: bigDecimalString,
  marketValue: bigDecimalString,
  pnl: bigDecimalString,
  forwardMarkValue: bigDecimalString,
  currency: z.string().length(3),
  versionHash: z.string(),
});

export const positionContributionSchema = z.object({
  positionId: z.string().uuid(),
  tradeId: z.string(),
  tradeLegId: z.string(),
  tradeVersion: z.number().int(),
  deliveryStart: instantString,
  deliveryEnd: instantString,
  quantity: bigDecimalString,
  volumeUnit: z.string(),
  deliveryPointId: z.string(),
  deliveryStatus: z.enum(['SETTLED', 'PARTIAL', 'FORWARD']),
  settledMw: bigDecimalString.nullable(),
  settledMwh: bigDecimalString.nullable(),
  avgPrice: bigDecimalString.nullable(),
  settledValue: bigDecimalString.nullable(),
  marketValue: bigDecimalString.nullable(),
  realizedPnl: bigDecimalString.nullable(),
  forwardMw: bigDecimalString.nullable(),
  forwardMwh: bigDecimalString.nullable(),
  forwardMarkValue: bigDecimalString.nullable(),
  unrealizedMtm: bigDecimalString.nullable(),
  currency: z.string().length(3),
});

export const dailyAggregateSchema = z.object({
  dayStart: instantString,
  dayEnd: instantString,
  dayStatus: z.enum(['SETTLED', 'TODAY', 'FORWARD']),
  intervalCount: z.number().int(),
  settledMw: bigDecimalString.nullable(),
  settledMwh: bigDecimalString.nullable(),
  avgPrice: bigDecimalString.nullable(),
  settledValue: bigDecimalString.nullable(),
  marketValue: bigDecimalString.nullable(),
  realizedPnl: bigDecimalString.nullable(),
  forwardMw: bigDecimalString.nullable(),
  forwardMwh: bigDecimalString.nullable(),
  curvePrice: bigDecimalString.nullable(),
  forwardMarkValue: bigDecimalString.nullable(),
  currency: z.string().length(3),
});

export const settlementCellSchema = z.object({
  id: z.string(),
  tenantId: z.string(),
  positionId: z.string().uuid(),
  intervalStart: instantString,
  intervalEnd: instantString,
  volumeMw: bigDecimalString,
  volumeMwh: bigDecimalString,
  price: bigDecimalString,
  amount: bigDecimalString,
  marketPrice: bigDecimalString,
  marketAmount: bigDecimalString,
  pnl: bigDecimalString,
  currency: z.string().length(3),
  computedAt: instantString,
});

export const forwardIntervalDetailSchema = z.object({
  intervalStart: instantString,
  intervalEnd: instantString,
  positionId: z.string().uuid().nullable(),
  tradeLegId: z.string().nullable(),
  resolvedQty: bigDecimalString,
  resolvedEnergy: bigDecimalString,
  multiplier: bigDecimalString,
  seriesKey: z.string(),
  evaluatedPrice: bigDecimalString.nullable(), // from ForwardMarkService (S4 Forward Curves + shaping), per ADR-002
  markValue: bigDecimalString.nullable(),
  curveId: z.string().nullable(),              // curve used for price evaluation
  curveVersion: z.number().int().nullable(),   // curve version at computation time
  currency: z.string().length(3).nullable(),
});

export const apiResponseSchema = <T extends z.ZodType>(dataSchema: T) =>
  z.object({
    data: dataSchema,
    meta: z.object({
      totalCount: z.number().optional(),
      offset: z.number().optional(),
      limit: z.number().optional(),
    }).optional(),
    error: z.object({
      code: z.string(),
      message: z.string(),
      correlationId: z.string().optional(),
    }).optional(),
  });

// Derived TypeScript types from Zod schemas
export type PortfolioSummaryDto = z.infer<typeof portfolioSummarySchema>;
export type RollupCellDto = z.infer<typeof rollupCellSchema>;
export type PositionContributionDto = z.infer<typeof positionContributionSchema>;
export type DailyAggregateDto = z.infer<typeof dailyAggregateSchema>;
export type SettlementCellDto = z.infer<typeof settlementCellSchema>;
export type ForwardIntervalDetailDto = z.infer<typeof forwardIntervalDetailSchema>;
```

### 11.3 Component Prop Types

All component prop types are defined in S5 above alongside their component descriptions. They follow the naming convention `{ComponentName}Props` and are exported as named types from each component file.

---

## S12 -- Project Structure

```
pv-ui/
  package.json
  tsconfig.json                          # strict: true, noUncheckedIndexedAccess: true
  vite.config.ts
  tailwind.config.ts                     # CSS-first config, design tokens
  index.html
  public/
  src/
    main.tsx                             # React root, QueryClient, Router
    app.tsx                              # App shell (sidebar, header, outlet)
    routes/
      __root.tsx                         # Root layout with sidebar + header
      dashboard/
        $portfolioId.tsx                 # Dashboard page route component
    api/
      client.ts                          # Typed fetch wrapper with tenant injection
      dashboard.ts                       # Dashboard API functions (one per endpoint)
      queryKeys.ts                       # Query key factory (dashboardKeys)
    hooks/
      useDashboardQueries.ts             # Custom hooks wrapping useQuery for each endpoint
      useTenantStore.ts                  # Zustand tenant store
      useAsOfClock.ts                    # Zustand as-of clock store
      useUserPreferences.ts              # Zustand user preferences store
      useDashboardSelection.ts           # Zustand selection state store
    components/
      primitives/
        NumericCell.tsx
        NumericCell.test.tsx
        NumericCell.stories.tsx
        StatusBadge.tsx
        StatusBadge.test.tsx
        StatusBadge.stories.tsx
        KpiTile.tsx
        KpiTile.test.tsx
        KpiTile.stories.tsx
        SkeletonRow.tsx
        VirtualizedTable.tsx
        VirtualizedTable.test.tsx
        VirtualizedTable.stories.tsx
        GranularityToggle.tsx
        SubGranularityToggle.tsx
        DateRangePicker.tsx
        DateRangePicker.test.tsx
        ConnectionStatus.tsx
      dashboard/
        PortfolioCard.tsx
        PortfolioCard.test.tsx
        PortfolioCard.stories.tsx
        PortfolioSummarySection.tsx
        RollupGrid.tsx
        RollupGrid.test.tsx
        RollupGrid.stories.tsx
        RollupGridToolbar.tsx
        PositionLedger.tsx
        PositionLedger.test.tsx
        PositionLedger.stories.tsx
        PositionLedgerToolbar.tsx
        IntervalDetailPanel.tsx
        IntervalDetailPanel.test.tsx
        IntervalDetailPanel.stories.tsx
        IntervalDetailHeader.tsx
        SettledDayGrid.tsx
        ForwardDayGrid.tsx
        MonthViewGrid.tsx
      layout/
        AppShell.tsx
        Sidebar.tsx
        Header.tsx
        AsOfToggle.tsx
        TenantDisplay.tsx
    lib/
      dateUtils.ts                       # DST-aware date formatting, UTC conversion
      dateUtils.test.ts
      numberUtils.ts                     # Intl.NumberFormat wrappers per precision
      numberUtils.test.ts
      statusUtils.ts                     # Period/delivery/day status derivation
      statusUtils.test.ts
    schemas/
      api.ts                             # Zod schemas from S11.2
      types.ts                           # Domain type aliases from S11.1
    styles/
      tokens.css                         # CSS custom properties (design tokens)
      globals.css                        # Tailwind base + global overrides
  e2e/
    dashboard.spec.ts                    # Playwright E2E tests
  .storybook/
    main.ts
    preview.ts
```

---

## S13 -- Number Formatting Rules

All number formatting uses `Intl.NumberFormat` with locale from `useUserPreferences().locale` (default `de-DE` for EU power context).

### Formatting Functions

```typescript
type NumericPrecision = 'PRICE' | 'MONETARY' | 'MW' | 'MWH' | 'INTERMEDIATE';

function formatNumber(
  value: number | string | null | undefined,
  precision: NumericPrecision,
  options?: {
    currency?: string;
    locale?: string;
    negativeStyle?: 'red' | 'parentheses' | 'both';
  }
): string;
```

### Rules

| Precision | Min Decimals | Max Decimals | Suffix | Alignment | Font |
|-----------|-------------|-------------|--------|-----------|------|
| PRICE | 2 | 8 | none | Right | Monospace |
| MONETARY | 2 | 4 | currency code | Right | Monospace |
| MW | 2 | 4 | "MW" | Right | Monospace |
| MWH | 2 | 4 | "MWh" | Right | Monospace |
| INTERMEDIATE | 2 | 10 | none | Right | Monospace |

**Negative numbers:** Controlled by user preference (`negativeNumberStyle`).
- `red`: Standard minus sign, text color `text-red-600` (light) / `text-red-400` (dark).
- `parentheses`: `(1,234.56)` instead of `-1,234.56`.
- `both`: Red text with parentheses.

**Null values:** Displayed as `--` (em-dash), not blank, not "0".

**Trailing zeros:** Trimmed to `minimumFractionDigits`. A price of `42.50000000` displays as `42.50`, not `42.5` (min 2) and not `42.50000000` (trimmed).

**Thousand separators:** Yes, per locale. `de-DE` uses `.` as thousand separator, `,` as decimal separator. `en-GB` uses `,` and `.`.

**Backend precision guarantee:** The backend enforces `PRICE` scale 8, `MONETARY` scale 4, `INTERMEDIATE` scale 10 via `NumericPrecision`. The UI must never display more decimal places than the backend provides, and must never silently truncate below the backend's scale. Since values arrive as strings, the UI preserves the backend's precision in the parsed `Number`.

---

## S14 -- Design Tokens

### Color Palette

Defined as CSS custom properties in `tokens.css`, consumed via Tailwind.

```css
:root {
  /* Backgrounds */
  --color-bg-primary: #ffffff;
  --color-bg-secondary: #f8f9fa;
  --color-bg-tertiary: #f1f3f5;
  --color-bg-grid-row-even: #f8f9fa;

  /* Text */
  --color-text-primary: #1a1a2e;
  --color-text-secondary: #495057;
  --color-text-muted: #868e96;

  /* Status */
  --color-status-settled: #2b8a3e;
  --color-status-transition: #e67700;
  --color-status-forward: #1971c2;
  --color-status-today: #e67700;

  /* Numeric */
  --color-negative: #c92a2a;
  --color-positive: #2b8a3e;

  /* Borders */
  --color-border-default: #dee2e6;
  --color-border-grid: #e9ecef;

  /* Interactive */
  --color-focus-ring: #1971c2;
  --color-row-selected: #e7f5ff;
  --color-row-hover: #f1f3f5;

  /* Flash (live update indicators -- reserved for future SSE) */
  --color-flash-up: rgba(43, 138, 62, 0.3);
  --color-flash-down: rgba(201, 42, 42, 0.3);
}

[data-theme="dark"] {
  --color-bg-primary: #0f0f23;
  --color-bg-secondary: #18181b;
  --color-bg-tertiary: #27272a;
  --color-bg-grid-row-even: #1c1c2e;

  --color-text-primary: #e4e4e7;
  --color-text-secondary: #a1a1aa;
  --color-text-muted: #71717a;

  --color-status-settled: #4ade80;
  --color-status-transition: #fbbf24;
  --color-status-forward: #60a5fa;
  --color-status-today: #fbbf24;

  --color-negative: #f87171;
  --color-positive: #4ade80;

  --color-border-default: #3f3f46;
  --color-border-grid: #27272a;

  --color-focus-ring: #60a5fa;
  --color-row-selected: #1e3a5f;
  --color-row-hover: #27272a;

  --color-flash-up: rgba(74, 222, 128, 0.3);
  --color-flash-down: rgba(248, 113, 113, 0.3);
}
```

### Typography

```css
:root {
  --font-sans: 'Inter', system-ui, -apple-system, sans-serif;
  --font-mono: 'JetBrains Mono', 'Fira Code', ui-monospace, monospace;

  --text-xs: 0.6875rem;    /* 11px -- grid cells */
  --text-sm: 0.75rem;      /* 12px -- secondary labels */
  --text-base: 0.8125rem;  /* 13px -- body text */
  --text-lg: 0.9375rem;    /* 15px -- section headers */
  --text-xl: 1.125rem;     /* 18px -- KPI values */
  --text-2xl: 1.5rem;      /* 24px -- headline KPI (total portfolio value) */
}
```

### Spacing

Standard Tailwind spacing scale. Grid-specific:
- Row height: 24px (`h-6`)
- Header height: 28px (`h-7`)
- Grid cell padding: `px-2 py-0.5`
- Section gap: `gap-4`

---

## S15 -- Responsive Behavior

This is an internal trading tool, not a public website. The primary target is desktop browsers at 1920x1080 and above. However, the layout should not break at narrower widths.

| Breakpoint | Behavior |
|-----------|----------|
| >= 1440px | Full layout as designed. L3 and L4 appear inline below L2. |
| 1024-1439px | L4 opens as a right drawer (50% width) instead of inline. |
| < 1024px | Warning banner: "This application is designed for desktop use. Some features may not display correctly." Grids allow horizontal scroll. |

No mobile-specific design. This is a deliberate trade-off for a data-dense trading UI.

---

## S16 -- Regulatory Display Requirements

### EMIR Labeling

All forward mark values (unrealized MtM) displayed anywhere in the UI must carry the label:

> "Current MtM (indicative)"

This appears as:
- A subtitle under the "Unrealized MtM" KPI on L1 portfolio cards.
- A column header annotation on L2: "Forward Mark Value (indicative)".
- A section header on L4 forward day view: "Forward Mark Data (Unrealized -- Indicative Only)".

This distinguishes ForwardMarkService-computed marks (indicative, computed on demand from current S4 Forward Curves × S6b Trade Interval Cache per ADR-002) from S5c EOD Mark Snapshots (not displayed -- official EMIR Art. 9 daily valuation). Per backend spec S11.

### Trade Reference Display

L3 position ledger always shows `tradeId` and `tradeLegId` columns to support cross-reference with RTS 22 transaction reports.

---

## S17 -- Open Items

| # | Item | Blocker? | Owner |
|---|------|----------|-------|
| UI-OI-1 | **Auth mechanism.** The API client needs to know how to authenticate. JWT via `Authorization: Bearer` header is assumed. The actual token acquisition flow (login page, OAuth redirect, etc.) is not designed in this spec. | Yes (for production) | Platform team |
| UI-OI-2 | **Portfolio list API.** This spec assumes the user navigates to `/dashboard/:portfolioId` directly. A portfolio list/selector page requires a `GET /api/portfolios` endpoint that is not in the backend tech spec. | No (for v1.0) | solutions-architect |
| UI-OI-3 | **As-of toggle backend support.** The As-Of Toggle UI is built but disabled. Backend must add `knowledgeTime` and `businessTime` query parameters to all dashboard endpoints. | No (deferred) | solutions-architect |
| UI-OI-4 | **Staleness endpoint.** AC-L1-08 requires comparing the S7 (Rollup Cells) curve/volume versions against current S4 (Forward Curves) versions (per ADR-002). Backend OI-5 defers this. The UI reserves space for the staleness badge but does not compute or display staleness. | No (deferred) | solutions-architect |
| UI-OI-5 | **Peak/off-peak toggle.** Backend currently sets `isPeak = false`. When `MarketCalendar` is implemented and peak data is materialized, the UI needs a toggle in L2. The column and filter infrastructure is built but the toggle is hidden until data exists. | No (deferred) | solutions-architect |
| UI-OI-6 | **i18n library selection.** The spec calls for all strings through an i18n layer. The recommended stack suggests `@lingui/react` or `react-intl`. Selection should happen at project bootstrap. For v1.0, English-only with i18n keys in place is acceptable. | No | ui-architect |
| UI-OI-7 | **Monospace font licensing.** JetBrains Mono is open source (SIL OFL). Verify that corporate licensing policy permits bundling it. Fallback: `ui-monospace`. | No | ui-architect |
| UI-OI-8 | **Number parsing precision.** The spec notes that `Number()` parsing is acceptable for display values within IEEE 754 range. If any L1 total portfolio value exceeds ~10^15 (unlikely but possible for large commodity desks), `Decimal.js` must be used for parsing. Add a runtime assertion that warns if a value exceeds `Number.MAX_SAFE_INTEGER`. | No | implementation |

---

*Hand-off: This spec is ready for review. Upon approval, implementation proceeds per Phase 2: project bootstrap, then design tokens, then primitives, then composed components, then screens, then routes, then wiring.*
