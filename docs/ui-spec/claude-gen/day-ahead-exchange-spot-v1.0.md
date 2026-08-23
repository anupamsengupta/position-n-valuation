# UI Technical Specification -- Day-Ahead Exchange Spot v1.0

## S1 -- Metadata

| Field | Value |
|---|---|
| Author | UI Architect (Claude Code) |
| Status | DRAFT -- pending approval |
| Version | 1.0 |
| Date | 2026-08-22 |
| Depends on | `docs/technical-spec/claude-gen/day-ahead-exchange-spot-v1.0.md` S16 (DA-UI-01 through DA-UI-09), S9.4 (REST endpoints), S4.3 (domain entities) |
| Linked functional spec | `docs/functional-spec/functional-spec-position-valuation-v1.0.md` |
| Layer classification | **Simulator-scope** (`pv-ui` + `pv-app` REST endpoints). No library-scope changes. |

---

## S2 -- Scope

### 2.1 In Scope

- Six new DA sub-pages: Import, Settlement, Nominations, Imbalance, Fees, Alerts
- DA layout shell with tab navigation and KPI strip
- Four new primitives: `ProgressStepper`, `DeviationCell`, `CategoryBadgeStrip`, `DstSeparatorRow`
- DA-specific status badge component (`DaStatusBadge`)
- Zustand stores for DA filter state and selection state
- TanStack Query hooks for all DA endpoints (11 queries, 3 mutations)
- SSE invalidation extension for DA-specific change types
- Route tree amendments with search-param-based filter persistence
- Sidebar navigation update to include DA entry
- `apiFetch` extension to support POST/PUT methods (currently GET-only)
- Zod schemas for all DA API response types
- DA-specific query key factory
- Component tests (Vitest + RTL) for all new components
- CSV file upload UI with drag-and-drop for triggering DA auction result imports (`POST /api/da/import/file`)
- Vite proxy configuration for DA SSE events

### 2.2 Out of Scope

- Imbalance "Show original" version comparison (backend spec UI-OI-2: endpoint not yet defined)
- Cross-navigation from existing `PositionLedger` to DA Settlement (backend spec UI-OI-3: requires `instrumentType` field on `PositionContributionDto`)
- Gate closure time returned by API (backend spec UI-OI-4: hardcoded 14:30 CET D-1 for now)
- Storybook stories (deferred to follow-up: the existing codebase has no Storybook setup)
- E2E tests with Playwright (deferred: no Playwright configuration exists yet)
- i18n (the existing codebase has no i18n layer; all strings are hardcoded English)

---

## S3 -- Information Architecture

### 3.1 Sidebar Redesign

The existing sidebar is a flat list of portfolio links. As the platform grows to support multiple instruments (DA Exchange Spot now, Intraday, PPA, Forwards later), the sidebar must evolve into a **persona-aware, section-grouped navigation** that serves the distinct workflows of each user role.

#### 3.1.1 Personas and Navigation Priorities

| Persona | Primary Concern | Top-Level Sections Used | Entry Point |
|---|---|---|---|
| **Trader / Portfolio Manager** | Real-time P&L, positions, market data, execution status | P&L Dashboard, Instruments (DA), Alerts | `/dashboard/$portfolioId` |
| **Operations / Middle Office** | Nominations, import status, scheduling gate closures | Instruments (DA > Nominations, Import), Alerts | `/da/nominations` |
| **Settlement / Back Office** | Settlement verification, fees, imbalance, payments | Instruments (DA > Settlement, Fees, Imbalance) | `/da/settlement` |
| **Risk / Compliance** | Exposure, mark-to-market, alert monitoring | P&L Dashboard, Alerts | `/dashboard/$portfolioId` |

All personas share the same sidebar. The sidebar is **not filtered by role** — all sections are visible to all users. The hierarchy is ordered by frequency of access across personas, with the most universal sections (P&L, Alerts) at the top.

#### 3.1.2 Sidebar Structure

```
+----------------------------------------------+
| [Logo/App Name]        PV Platform    [<<]   |  <- Collapse toggle
+----------------------------------------------+
|                                              |
| [search icon] Quick Search...          Ctrl+K|  <- Command palette trigger
|                                              |
+----------------------------------------------+
| DASHBOARDS                                   |  <- Section header
|   [chart icon] P&L Overview                  |  <- /dashboard (default portfolio)
|     > WIND_DE                                |  <- /dashboard/WIND_DE
|     > SOLAR_FR                               |  <- /dashboard/SOLAR_FR
|     > THERMAL_AT                             |  <- /dashboard/THERMAL_AT
|                                              |
+----------------------------------------------+
| INSTRUMENTS                                  |  <- Section header
|   [zap icon] Day-Ahead                       |  <- /da (collapsible)
|     > Import                                 |  <- /da/import
|     > Settlement                             |  <- /da/settlement
|     > Nominations                            |  <- /da/nominations
|     > Imbalance                              |  <- /da/imbalance
|     > Fees                                   |  <- /da/fees
|   [clock icon] Intraday          (coming)    |  <- Disabled, future
|   [trending icon] PPA            (coming)    |  <- Disabled, future
|   [bar-chart icon] Forwards      (coming)    |  <- Disabled, future
|                                              |
+----------------------------------------------+
| OPERATIONS                                   |  <- Section header
|   [bell icon] Alerts               [3]       |  <- /da/alerts + badge count
|                                              |
+----------------------------------------------+
|                                              |
| [spacer — pushes footer to bottom]           |
|                                              |
+----------------------------------------------+
| [settings icon] Settings                     |  <- Future
| [help icon] Help                             |  <- Future
+----------------------------------------------+
```

#### 3.1.3 Design Principles

1. **Collapsible sidebar.** Toggle between expanded (220px) and icon-only (48px) modes. State persisted in `useUserPreferences`. Collapsed mode shows only icons; hovering reveals a tooltip with the label. Keyboard shortcut: `Ctrl+B` (same convention as VS Code, Jira, Linear).

2. **Section headers** (`DASHBOARDS`, `INSTRUMENTS`, `OPERATIONS`) are uppercase, muted, non-interactive labels. They visually group related navigation items.

3. **Collapsible sub-items.** "P&L Overview" and "Day-Ahead" are parent items that expand/collapse their children. Expanded state is persisted in `useUserPreferences.sidebarExpanded` as a `Set<string>` of section keys. Default: all expanded.

4. **Alert badge.** The "Alerts" link shows a live count of open CRITICAL+WARNING alerts from `useDaAlertCounts`. The badge is a red pill for CRITICAL, amber for WARNING-only, hidden when zero. This gives all personas immediate visibility into operational issues without navigating to the alerts page.

5. **Quick search / command palette.** The search input at the top triggers a command palette overlay (future feature). For v1, it is a styled placeholder that shows the keyboard shortcut hint but is non-functional. Clicking it or pressing `Ctrl+K` shows a "Coming soon" toast.

6. **Future instrument entries.** Intraday, PPA, and Forwards are shown as disabled items with "(coming)" suffix. This communicates the platform's roadmap and establishes the information architecture early. Disabled items have `opacity-50 cursor-not-allowed` and no navigation behavior.

7. **Active state.** The active link is highlighted with `bg-interactive-row-selected` (same token as grid row selection). For parent items, if any child route is active, the parent is also highlighted with a subtle left border accent.

8. **Icons.** The codebase currently uses Unicode characters for icons. This sidebar design introduces SVG icons via inline `<svg>` elements (no icon library dependency). Each section and parent item gets a 16x16 SVG icon. The icon set is minimal (8 icons total) and self-contained in a `SidebarIcons.tsx` file.

#### 3.1.4 Collapsed Mode

When collapsed (icon-only, 48px wide):
- Section headers are hidden.
- Only parent-level icons are shown, vertically stacked.
- Hovering an icon shows a tooltip with the label (using Radix UI Tooltip, already a dependency).
- Clicking a parent icon navigates to its default child route.
- The alert badge appears as a small red/amber dot on the bell icon.
- The collapse toggle button (`<<` / `>>`) is always visible at the top.

```
+------+
| [<<] |
+------+
| [Q]  |  <- Quick search (disabled in collapsed)
+------+
| [P]  |  <- P&L Dashboard
| [Z]  |  <- Day-Ahead
| [B]  |  <- Alerts (with dot badge)
+------+
| [S]  |  <- Settings
| [?]  |  <- Help
+------+
```

#### 3.1.5 Sidebar Component Changes

**File:** `pv-ui/src/components/layout/Sidebar.tsx` — **full rewrite** (not amendment).

**New sub-components:**

| Component | File | Purpose |
|---|---|---|
| `SidebarSection` | `pv-ui/src/components/layout/SidebarSection.tsx` | Collapsible section with header label + child links |
| `SidebarLink` | `pv-ui/src/components/layout/SidebarLink.tsx` | Navigation link with icon, label, optional badge, active state |
| `SidebarIcons` | `pv-ui/src/components/layout/SidebarIcons.tsx` | SVG icon definitions for sidebar items |
| `AlertBadge` | `pv-ui/src/components/layout/AlertBadge.tsx` | Live alert count badge (red/amber pill or dot) |

**Props for `Sidebar`:**
```typescript
interface SidebarProps {
  className?: string;
}
// Sidebar reads collapsed state from useUserPreferences
// Sidebar reads portfolio list from usePortfolios
// Sidebar reads alert counts from useDaAlertCounts
```

**Accessibility:**
- `<nav aria-label="Main navigation">` on the root element.
- Collapsible sections use `aria-expanded` on the toggle button and `aria-hidden` on collapsed content.
- Collapsed mode tooltips use Radix Tooltip with `aria-label` on each icon button.
- `Ctrl+B` shortcut announced via `aria-keyshortcuts` on the collapse toggle.
- Alert badge uses `aria-label="3 open alerts"` (dynamic count).
- Disabled future instrument items use `aria-disabled="true"`.

#### 3.1.6 Responsive Behavior

- **Desktop (>1280px):** Sidebar expanded by default. User can collapse.
- **Tablet (768-1280px):** Sidebar collapsed by default. User can expand (overlays content as a drawer).
- **Mobile (<768px):** Sidebar hidden. Hamburger menu in header opens sidebar as a full-width drawer with backdrop.

The responsive breakpoints match existing Tailwind config. The drawer behavior uses CSS `transform: translateX()` transitions.

### 3.2 URL Structure

| Path | Component | Search Params |
|---|---|---|
| `/da` | `DaLayout` (redirects to `/da/import`) | -- |
| `/da/import` | `DaImportPage` | `status?`, `zone?`, `dateFrom?`, `dateTo?` |
| `/da/import/$sessionId` | `DaImportPage` (detail panel open) | -- |
| `/da/settlement` | `DaSettlementPage` | `deliveryDay`, `zone` |
| `/da/nominations` | `DaNominationPage` | `deliveryDay`, `zone?`, `bg?` |
| `/da/imbalance` | `DaImbalancePage` | `deliveryDay?`, `bg?`, `yearMonth?`, `view` |
| `/da/fees` | `DaFeesPage` | `deliveryDay` |
| `/da/alerts` | `DaAlertsPage` | `status?`, `severity?`, `category?`, `dateFrom?`, `dateTo?` |

All search params default to sensible values when omitted (today's date for `deliveryDay`, first available zone for `zone`, "daily" for `view`).

### 3.3 Entry Points

- **Sidebar > Instruments > Day-Ahead:** Expands to show 5 sub-links (Import, Settlement, Nominations, Imbalance, Fees). Clicking "Day-Ahead" itself navigates to `/da` (redirects to `/da/import`).
- **Sidebar > Operations > Alerts:** Navigates to `/da/alerts`. Badge shows live count of open critical/warning alerts.
- **Sidebar > Dashboards > P&L Overview > {portfolio}:** Navigates to `/dashboard/$portfolioId` (existing behavior, now under a section header).
- **Import detail "View Trades" link:** navigates to `/da/settlement?deliveryDay=...&zone=...`.
- **Imbalance monthly row click:** sets `deliveryDay` filter and switches to daily view.
- **KPI strip tiles:** each tile links to the corresponding detail page.
- **Alert row drill:** alert detail shows delivery day and zone, with link to relevant settlement view.

---

## S4 -- Screens and Layouts

### 4.1 DaLayout (parent layout for all DA routes)

**Location:** `/da`

**Structure:**
```
+----------------------------------------------------------+
| DaNavTabs: [Import] [Settlement] [Nominations] ...       |
+----------------------------------------------------------+
| DaKpiStrip: 6 KPI tiles in a horizontal row              |
|   Net Volume | VWAP | Settlement | Fees | Imbalance | Alerts
+----------------------------------------------------------+
| <Outlet /> -- child route content                        |
+----------------------------------------------------------+
```

**Loading state:** KPI strip shows 6 `SkeletonCard` instances. Tab group renders immediately (static content).

**Error state:** KPI strip shows error boundary fallback. Child route content is wrapped in its own error boundary.

**Empty state:** Not applicable (layout always has tabs and KPI).

**Keyboard:** Tab order: tabs -> KPI tiles -> child content. Arrow keys within tab group cycle tabs (roving tabindex). Enter/Space activates a tab.

### 4.2 DaImportPage

**Primary tasks:** Upload CSV auction result files, view import history, monitor active imports, inspect import details.

**Structure:**
```
+----------------------------------------------------------+
| DaFileUploadArea                                          |
|   +----------------------------------------------------+ |
|   |  [drag-and-drop zone]                              | |
|   |  Drop EPEX CSV file here, or [Browse] to select    | |
|   |  Accepted: .csv files (EPEX auction result format)  | |
|   +----------------------------------------------------+ |
|   | [Upload progress bar / spinner when uploading]      | |
+----------------------------------------------------------+
| DaImportHistoryTable                                      |
|   [Filter bar: status, zone, date range]                 |
|   +----------------------------------------------------+ |
|   | Delivery Day | Zone | Status | Trades | MWh | Time | |
|   |---+----------+------+--------+--------+-----+------| |
|   | > | 2026-09-16 | DE_LU | IMPORTED | 96 | 4800 | 14:02 | |
|   | . | 2026-09-15 | DE_LU | IMPORTED | 96 | 4650 | 14:01 | |
|   +----------------------------------------------------+ |
+----------------------------------------------------------+
| DaImportDetailPanel (conditional, when row selected)      |
|   +----------------------------------------------------+ |
|   | ProgressStepper: [PENDING]-[VALIDATING]-[IMPORTED]  | |
|   | Status: DaStatusBadge                               | |
|   | Exchange Total: 4800.0000 MWh                       | |
|   | Imported Total: 4800.0000 MWh [match indicator]     | |
|   | [View Trades] [Retry Import]                        | |
|   +----------------------------------------------------+ |
+----------------------------------------------------------+
```

**DaFileUploadArea behavior:**
- Drag-and-drop zone accepts `.csv` files. Also provides a `<input type="file" accept=".csv">` via the "Browse" button.
- On file drop/select, sends `POST /api/da/import/file` as `multipart/form-data` with the file and `tenantId`.
- Shows upload progress via a spinner and status text ("Uploading...", "Processing...", "Import started").
- On success, invalidates `daKeys.importHistory` and navigates to the import detail view for the new session.
- On error, shows an inline error message with the server's error text and a "Try Again" button.
- Maximum file size: 10 MB (validated client-side before upload).
- Restricts to single file at a time.

**Loading:** History table shows `SkeletonTable`. Detail panel shows skeleton of progress stepper + fields.

**Error:** Table shows error boundary fallback with retry. Detail panel shows error with retry. Upload area shows inline error.

**Empty:** Table shows `EmptyState` with message "No import sessions found. Upload a CSV file to get started.".

**Keyboard:**
- Arrow Up/Down in table moves between rows.
- Enter on a row opens the detail panel (focus moves to panel heading).
- Escape in detail panel closes it (focus returns to the row).
- Tab between filter bar and table.

### 4.3 DaSettlementPage

**Primary tasks:** View interval-level settlement data for a delivery day, identify negative prices, verify DST handling.

**Structure:**
```
+----------------------------------------------------------+
| DaSettlementFilterBar                                     |
|   Delivery Day: [date picker]  Zone: [dropdown]          |
|   [HideZeroToggle]                                       |
+----------------------------------------------------------+
| DaSettlementGrid                                          |
|   +----------------------------------------------------+ |
|   | Interval(CET) | Price | Volume | Dir | Energy | Amt | Status |
|   |---+----------+-------+--------+-----+--------+-----+--------+
|   | 00:00 CET    | 45.12 |  10.00 | BUY | 2.50   | 112 | SETTLED|
|   | 00:15 CET    | 45.08 |  10.00 | BUY | 2.50   | 112 | SETTLED|
|   | ...                                                  |
|   | 01:45 CET    | 42.10 |  10.00 | BUY | 2.50   | 105 | SETTLED|
|   | --- DST spring-forward: 02:00-03:00 CET skipped --- |
|   | 03:00 CEST   | 44.50 |  10.00 | BUY | 2.50   | 111 | SETTLED|
|   | ...                                                  |
|   +----------------------------------------------------+ |
| DaSettlementSummaryRow                                    |
|   Total Energy: 2400 MWh | Settlement: EUR 108,000 | VWAP: 45.00 |
+----------------------------------------------------------+
```

**Loading:** Grid shows `SkeletonTable` with 7 columns matching the column widths.

**Error:** Error boundary fallback.

**Empty:** `EmptyState` with "No settlement data for the selected delivery day and zone".

**DST:** Spring-forward day shows 92 interval rows with `DstSeparatorRow`. Fall-back day shows 100 rows with `DstSeparatorRow`. Normal day shows 96 rows. The grid `aria-label` includes DST status.

**Negative price:** Price cell turns amber with tooltip. BUY rows with negative price show amount in green with "CR" suffix.

### 4.4 DaNominationPage

**Primary tasks:** Compare traded vs nominated volumes, identify deviations, monitor gate closure.

**Structure:**
```
+----------------------------------------------------------+
| [Nomination not submitted banner - conditional]           |
|   WARNING: Nomination not submitted. Gate closure in 2h.  |
+----------------------------------------------------------+
| DaNominationFilterBar                                     |
|   Delivery Day: [date picker]  BG: [dropdown / All]     |
+----------------------------------------------------------+
| DaNominationGrid                                          |
|   +----------------------------------------------------+ |
|   | Interval(CET) | Traded(MW) | Nominated(MW) | Dev(MW) | Status |
|   |---+-----------+------------+---------------+---------+--------+
|   | 00:00 CET     |   10.00    |    10.00      |  0.00   |   OK   |
|   | 00:15 CET     |   10.00    |     8.00      | -2.00   |  DEV   |
|   | 00:30 CET     |   10.00    |      --       |   --    | MISSING|
|   +----------------------------------------------------+ |
| DaNominationSummaryBar                                    |
|   Traded: 2400 MWh | Nominated: 2380 MWh | Dev: -20 MWh | 3 intervals with deviation |
+----------------------------------------------------------+
```

**Gate closure banner:** Appears when `nominationSubmitted === false`. Severity based on time remaining: > 4h = INFO (blue), 1-4h = WARNING (amber), < 1h = CRITICAL (red). Countdown updates every minute via `setInterval`. Uses `role="alert"`.

### 4.5 DaImbalancePage

**Primary tasks:** View imbalance settlement, switch between daily and monthly views, compare TSO corrections.

**Structure (daily view):**
```
+----------------------------------------------------------+
| View: [Daily] [Monthly]                                   |
| [TSO correction banner - conditional]                     |
+----------------------------------------------------------+
| DaImbalanceDailyGrid                                      |
|   | Interval | Nominated | Actual | Imbalance | reBAP | Amount |
|   |---+------+-----------+--------+-----------+-------+--------+
|   | 00:00    |  10.00    | 10.50  |   +0.50   | 35.00 | +17.50 |
|   | 00:15    |  10.00    |  9.20  |   -0.80   | 42.00 | -33.60 |
+----------------------------------------------------------+
| DaImbalanceSummaryBar                                     |
|   Net Imbalance: -5.2 MWh | Net Cost: EUR -1,200 | Max: 2.1 MW at 14:30 |
+----------------------------------------------------------+
```

**Structure (monthly view):**
```
+----------------------------------------------------------+
| DaImbalanceMonthlyGrid                                    |
|   | Day | Net MWh | Net Cost | Max Dev | Intervals w/ Imb |
|   |---+---------+---------+----------+---------+----------+
|   | 01 Sep |  -5.2  | -1,200 |   2.1   |    12    |
|   | 02 Sep |  +1.8  |   +450 |   0.9   |     4    |
|   | ...   clickable to drill into daily view              |
+----------------------------------------------------------+
| Monthly Summary                                           |
|   Total Net Imbalance: -42.5 MWh | Total Net Cost: EUR -8,400 |
+----------------------------------------------------------+
```

### 4.6 DaFeesPage

**Primary tasks:** View fee breakdown for a delivery day.

**Structure:**
```
+----------------------------------------------------------+
| Delivery Day: [date picker]                               |
| Fee Schedule: effective 2026-01-01 | Tier: Standard      |
+----------------------------------------------------------+
| DaFeeBreakdownTable                                       |
|   | Fee Type      | Rate (EUR/MWh) | Volume (MWh) | Amount (EUR) |
|   |---+-----------+----------------+--------------+--------------+
|   | Trading Fee   |     0.0500     |   4,800.00   |    240.00    |
|   | Clearing Fee  |     0.0300     |   4,800.00   |    144.00    |
|   | Market Op Fee |     0.0100     |   4,800.00   |     48.00    |
|   |---+-----------+----------------+--------------+--------------+
|   | TOTAL         |                |   4,800.00   |    432.00    |
+----------------------------------------------------------+
| Note: Fees computed on gross volume: BUY 2400 + SELL 2400 = 4800 MWh |
+----------------------------------------------------------+
```

### 4.7 DaAlertsPage

**Primary tasks:** Triage operational alerts, acknowledge and resolve issues, filter by category/severity.

**Structure:**
```
+----------------------------------------------------------+
| CategoryBadgeStrip                                        |
|   [All (12)] [Ingestion (3)] [Prices (2)] [Nominations (4)] ... |
+----------------------------------------------------------+
| DaAlertFilterBar                                          |
|   Severity: [multi-select] Status: [OPEN|ACK|RESOLVED]  |
|   Date range: [from] - [to]  [Clear filters]            |
+----------------------------------------------------------+
| DaAlertList                                               |
|   +----------------------------------------------------+ |
|   | CRIT | Ingestion | Volume mismatch -10 MWh | 16 Sep | DE_LU | 14:02 | OPEN |
|   |   [expanded: source event, details, Acknowledge btn]|
|   | WARN | Nominations | Deviation > 5 MW  | 16 Sep | DE_LU | 13:45 | OPEN |
|   | INFO | Settlement | Revaluation complete | 15 Sep | DE_LU | 12:00 | RESOLVED |
|   +----------------------------------------------------+ |
+----------------------------------------------------------+
```

**Keyboard shortcuts (context: alert list focused):**
- Arrow Up/Down: navigate rows
- Enter: expand/collapse detail
- A: acknowledge (OPEN alerts only, with aria confirmation)
- R: resolve (ACKNOWLEDGED alerts only)
- Escape: collapse detail / clear selection
- ?: show shortcut help tooltip

**Focus management:** After acknowledge/resolve, focus stays on the same row. `aria-live="polite"` region announces status change.

---

## S5 -- Components

### 5.1 New Primitives

#### 5.1.1 ProgressStepper

**File:** `pv-ui/src/components/primitives/ProgressStepper.tsx`

```typescript
interface ProgressStepperProps {
  steps: readonly string[];
  currentStep: string;
  terminalStates?: readonly string[];
  errorStates?: readonly string[];
  className?: string;
}
```

**State:** None (fully controlled via props).

**Accessibility:** `role="progressbar"`, `aria-valuemin=0`, `aria-valuemax={steps.length}`, `aria-valuenow={currentIndex}`, `aria-valuetext={currentStep}`. Each step circle has `aria-label` with step name and state (completed/current/pending/error).

**Storybook stories (future):** Default, Midway, Completed, Error state, Single step.

#### 5.1.2 DeviationCell

**File:** `pv-ui/src/components/primitives/DeviationCell.tsx`

```typescript
interface DeviationCellProps {
  value: string | number | null | undefined;
  precision: NumericPrecision;
  thresholds?: { minor: number; significant: number };
  className?: string;
}
```

**State:** None.

**Accessibility:** `aria-label` includes severity text: "Deviation: negative 2.0 MW, minor deviation".

**Relationship to NumericCell:** Wraps `NumericCell` internally, adding threshold-based color class via `cn()`.

#### 5.1.3 CategoryBadgeStrip

**File:** `pv-ui/src/components/primitives/CategoryBadgeStrip.tsx`

```typescript
interface CategoryBadgeStripProps {
  categories: Array<{ key: string; label: string; count: number }>;
  activeCategory: string | null;
  onCategoryClick: (key: string | null) => void;
  className?: string;
}
```

**State:** Roving tabindex state managed via `useRef` array (same pattern as `GranularityToggle`).

**Accessibility:** `role="toolbar"` on container. Each badge is a `<button>` with `aria-pressed`. Arrow keys cycle focus. Enter/Space activates.

#### 5.1.4 DstSeparatorRow

**File:** `pv-ui/src/components/primitives/DstSeparatorRow.tsx`

```typescript
interface DstSeparatorRowProps {
  type: 'spring-forward' | 'fall-back';
  columnCount: number;
}
```

**State:** None.

**Accessibility:** `role="presentation"`. Not focusable during grid navigation. Text content is accessible to screen readers.

**Relationship to DayBoundaryRow:** Same structural pattern (non-data row with `colSpan`), different purpose. Existing `DayBoundaryRow` separates calendar days; this marks DST transitions within a day.

### 5.2 DA-Specific Components

#### 5.2.1 DaStatusBadge

**File:** `pv-ui/src/components/da/DaStatusBadge.tsx`

```typescript
type DaStatus =
  | 'IMPORTED' | 'IMPORTING' | 'VALIDATING' | 'PENDING'
  | 'VALIDATED'
  | 'VALIDATION_FAILED' | 'IMPORT_FAILED'
  | 'BUY' | 'SELL'
  | 'CRITICAL' | 'WARNING' | 'INFO'
  | 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED'
  | 'OK' | 'DEVIATION' | 'MISSING';

interface DaStatusBadgeProps {
  status: DaStatus;
  size?: 'sm' | 'md';
}
```

**Design rationale (from S16.4.5):** Separate component from existing `StatusBadge` to avoid conflating delivery statuses with alert/import/direction statuses. Same visual pattern (pill with icon + text), different type contract.

**Status config map:**

| Status | Label | Background | Icon | Text Color |
|---|---|---|---|---|
| `IMPORTED` | Imported | `bg-status-settled` | checkmark | white |
| `IMPORTING` | Importing | `bg-status-forward` | spinner | white |
| `VALIDATING` | Validating | `bg-status-forward` | spinner | white |
| `VALIDATED` | Validated | `bg-status-settled` | checkmark | white |
| `PENDING` | Pending | `bg-bg-tertiary` | clock | `text-text-secondary` |
| `VALIDATION_FAILED` | Failed | `bg-red-600 dark:bg-red-500` | X | white |
| `IMPORT_FAILED` | Failed | `bg-red-600 dark:bg-red-500` | X | white |
| `BUY` | BUY | `bg-status-settled` | -- | white |
| `SELL` | SELL | `bg-red-600 dark:bg-red-500` | -- | white |
| `CRITICAL` | CRIT | `bg-red-600 dark:bg-red-500` | -- | white |
| `WARNING` | WARN | `bg-status-transition` | -- | white/dark |
| `INFO` | INFO | `bg-status-forward` | -- | white |
| `OPEN` | Open | `bg-red-600 dark:bg-red-500` | -- | white |
| `ACKNOWLEDGED` | Ack'd | `bg-status-transition` | -- | white/dark |
| `RESOLVED` | Resolved | `bg-status-settled` | checkmark | white |
| `OK` | OK | `bg-status-settled` | checkmark | white |
| `DEVIATION` | Deviation | `bg-status-transition` | -- | white/dark |
| `MISSING` | Missing | `bg-red-600 dark:bg-red-500` | -- | white |

#### 5.2.2 DaLayout

**File:** `pv-ui/src/components/da/DaLayout.tsx`

**Props:** None (reads route context and Zustand stores).

**Children:** `DaNavTabs`, `DaKpiStrip`, `<Outlet />`.

**Error boundary:** Wraps each section independently.

#### 5.2.3 DaNavTabs

**File:** `pv-ui/src/components/da/DaNavTabs.tsx`

```typescript
// No external props -- reads current route path to determine active tab
```

**Tab definitions:**

| Tab | Label | Path | Badge |
|---|---|---|---|
| Import | Import | `/da/import` | -- |
| Settlement | Settlement | `/da/settlement` | -- |
| Nominations | Nominations | `/da/nominations` | -- |
| Imbalance | Imbalance | `/da/imbalance` | -- |
| Fees | Fees | `/da/fees` | -- |
| Alerts | Alerts | `/da/alerts` | Open alert count (from KPI) |

**Accessibility:** `role="tablist"` / `role="tab"` / `aria-selected`. Roving tabindex with arrow keys.

#### 5.2.4 DaKpiStrip

**File:** `pv-ui/src/components/da/DaKpiStrip.tsx`

```typescript
// No external props -- consumes useDaKpi() hook
```

**Tiles (using existing `KpiTile` primitive):**

| Tile | Value source | Precision | Color logic |
|---|---|---|---|
| Net Volume | `netVolumeMwh` | `MWH` | Green if positive (net BUY), red if negative |
| VWAP | `vwap` | `PRICE` | Neutral |
| Settlement | `settlementTotal` | `MONETARY` | Neutral |
| Fees | `exchangeFees` | `MONETARY` | Neutral |
| Imbalance Cost | `imbalanceCost` | `MONETARY` | Green if positive (receipt), red if negative |
| Open Alerts | `openAlertCount` | -- (integer) | Red if CRITICAL, amber if WARNING, neutral otherwise |

#### 5.2.5 DaImportHistoryTable

**File:** `pv-ui/src/components/da/DaImportHistoryTable.tsx`

```typescript
interface DaImportHistoryTableProps {
  data: AuctionImportSessionDto[] | undefined;
  isLoading: boolean;
  selectedSessionId: string | null;
  onRowClick: (session: AuctionImportSessionDto) => void;
}
```

**Columns:** Delivery Day, Zone, Status (DaStatusBadge), Trades (count from `tradeIds`), Total MWh (`importedTotalMwh`, NumericCell), Imported At (formatted timestamp).

**Sorting:** Default by `importTimestamp` DESC. Column header click toggles sort direction. Uses `@tanstack/react-table` column definitions.

**Row height:** 24px. Virtualization via `@tanstack/react-virtual` if row count exceeds 50.

#### 5.2.6 DaImportDetailPanel

**File:** `pv-ui/src/components/da/DaImportDetailPanel.tsx`

```typescript
interface DaImportDetailPanelProps {
  session: AuctionImportSessionDto;
  isPolling: boolean;
  onClose: () => void;
  onRetry: () => void;
}
```

**Content:** ProgressStepper, DaStatusBadge, numeric fields (NumericCell), volume match indicator, validation errors list, action buttons.

**Volume match:** Green checkmark icon if `exchangeReportedTotalMwh === importedTotalMwh`. Red X icon otherwise. Both use text labels, not color alone.

#### 5.2.7 through 5.2.16

Remaining DA page components (`DaSettlementPage`, `DaSettlementGrid`, `DaSettlementSummaryRow`, `DaNominationPage`, `DaNominationGrid`, `DaNominationSummaryBar`, `DaImbalancePage`, `DaImbalanceDailyGrid`, `DaImbalanceMonthlyGrid`, `DaImbalanceSummaryBar`, `DaFeesPage`, `DaFeeBreakdownTable`, `DaAlertsPage`, `DaAlertFilterBar`, `DaAlertList`, `DaAlertRow`) follow the patterns established by the existing dashboard components (`RollupGrid`, `PositionLedger`, `SettledDayGrid`, `DashboardFilterBar`). Their props, state, and rendering are specified in S4 screen layouts above and S16.3 of the backend spec.

---

## S6 -- State

### 6.1 Server State (TanStack Query)

#### 6.1.1 Query Key Factory

**File:** `pv-ui/src/api/daQueryKeys.ts`

```typescript
export const daKeys = {
  all: ['da'] as const,

  importHistory: (tenantId: string, filters: DaImportFilters) =>
    [...daKeys.all, 'import-history', tenantId, filters] as const,

  importDetail: (tenantId: string, sessionId: string) =>
    [...daKeys.all, 'import-detail', tenantId, sessionId] as const,

  settlement: (tenantId: string, deliveryDay: string, zone: string, timezone: string) =>
    [...daKeys.all, 'settlement', tenantId, deliveryDay, zone, timezone] as const,

  nominations: (tenantId: string, deliveryDay: string, zone: string, bgId: string | null) =>
    [...daKeys.all, 'nominations', tenantId, deliveryDay, zone, bgId] as const,

  balancingGroups: (tenantId: string) =>
    [...daKeys.all, 'balancing-groups', tenantId] as const,

  imbalanceDaily: (tenantId: string, deliveryDay: string, bgId: string) =>
    [...daKeys.all, 'imbalance-daily', tenantId, deliveryDay, bgId] as const,

  imbalanceMonthly: (tenantId: string, yearMonth: string, bgId: string) =>
    [...daKeys.all, 'imbalance-monthly', tenantId, yearMonth, bgId] as const,

  fees: (tenantId: string, deliveryDay: string) =>
    [...daKeys.all, 'fees', tenantId, deliveryDay] as const,

  kpi: (tenantId: string, deliveryDay: string, zone: string) =>
    [...daKeys.all, 'kpi', tenantId, deliveryDay, zone] as const,

  alerts: (tenantId: string, filters: DaAlertFilters) =>
    [...daKeys.all, 'alerts', tenantId, filters] as const,

  alertCounts: (tenantId: string, status: string | null) =>
    [...daKeys.all, 'alert-counts', tenantId, status] as const,
} as const;
```

Every key includes `tenantId` to prevent cross-tenant cache leaks. Filter objects are serialized into the key so different filter combinations have distinct cache entries.

#### 6.1.2 Staleness and Refresh Configuration

| Query | staleTime | gcTime | refetchInterval | Notes |
|---|---|---|---|---|
| `importHistory` | 30s | 300s | 60s | Refetch on `DA_IMPORT_COMPLETED` SSE |
| `importDetail` | 0 | 300s | 2s (non-terminal) / false (terminal) | Dynamic interval via callback |
| `settlement` | 120s | 300s | -- | Refetch on `DA_SETTLEMENT_UPDATED` SSE |
| `nominations` | 60s | 300s | 120s | Nominations can change pre-delivery |
| `balancingGroups` | 300s | 600s | -- | Reference data |
| `imbalanceDaily` | 120s | 300s | -- | Stable after TSO publication |
| `imbalanceMonthly` | 120s | 300s | -- | Aggregated view |
| `fees` | 300s | 300s | -- | Stable once computed |
| `kpi` | 30s | 300s | 60s | Frequent refresh for dashboard feel |
| `alerts` | 10s | 300s | 30s | Fast refresh for alert awareness |
| `alertCounts` | 10s | 300s | 30s | Drives badge counts |

#### 6.1.3 Invalidation Triggers (SSE Extension)

The existing `useRealtimeInvalidation` hook at `pv-ui/src/hooks/useRealtimeInvalidation.ts` must be amended with three new `ChangeType` values and corresponding `INVALIDATION_MAP` entries:

```typescript
// New ChangeType values (added to the union):
| 'DA_IMPORT_COMPLETED'
| 'DA_ALERT_RAISED'
| 'DA_SETTLEMENT_UPDATED'

// New INVALIDATION_MAP entries:
DA_IMPORT_COMPLETED: ['da'],
DA_ALERT_RAISED: ['da'],
DA_SETTLEMENT_UPDATED: ['da'],
```

The invalidation uses the broad `['da']` prefix. This is intentional for v1: DA queries are inexpensive and a full invalidation on any DA event is acceptable. If performance becomes an issue, the invalidation can be narrowed to specific sub-keys (e.g., `['da', 'import-history']`).

### 6.2 Client State (Zustand)

#### 6.2.1 useDaFilters

**File:** `pv-ui/src/hooks/useDaFilters.ts`

```typescript
interface DaFiltersState {
  deliveryDay: string;          // YYYY-MM-DD, default today
  zone: string;                 // default 'DE_LU'
  balancingGroupId: string | null; // null = "All"
  yearMonth: string;            // YYYY-MM, default current month
  imbalanceView: 'daily' | 'monthly';

  // Import filters
  importStatusFilter: string | null;
  importZoneFilter: string | null;
  importDateFrom: string | null;
  importDateTo: string | null;

  // Alert filters
  alertStatusFilter: string | null;
  alertSeverityFilter: string[];
  alertCategoryFilter: string | null;
  alertDateFrom: string | null;
  alertDateTo: string | null;

  // Actions
  setDeliveryDay: (day: string) => void;
  setZone: (zone: string) => void;
  setBalancingGroupId: (bgId: string | null) => void;
  setYearMonth: (ym: string) => void;
  setImbalanceView: (view: 'daily' | 'monthly') => void;
  setImportFilters: (filters: Partial<ImportFilters>) => void;
  setAlertFilters: (filters: Partial<AlertFilters>) => void;
  clearAlertFilters: () => void;
}
```

**Pattern:** Same as `useDashboardFilters`. No localStorage persistence (filters are persisted in URL search params instead). Store is the source of truth; URL search params are synced bidirectionally on route navigation.

#### 6.2.2 useDaSelection

**File:** `pv-ui/src/hooks/useDaSelection.ts`

```typescript
interface DaSelectionState {
  // Import page
  selectedSessionId: string | null;
  setSelectedSession: (id: string | null) => void;

  // Alerts page
  selectedAlertId: string | null;
  expandedAlertId: string | null;
  setSelectedAlert: (id: string | null) => void;
  setExpandedAlert: (id: string | null) => void;

  clearAll: () => void;
}
```

Simpler than `useDashboardSelection` because DA pages have flat selection (no multi-level drill-down cascade).

### 6.3 Form State

The only form input in DA is the CSV file upload on the Import page. The file upload state is local to `DaFileUploadArea` (no Zustand store needed):

```typescript
// Local state in DaFileUploadArea
const [isDragOver, setIsDragOver] = useState(false);   // Drag visual feedback
const [uploadError, setUploadError] = useState<string | null>(null);
```

The upload mutation is defined in `useDaMutations.ts` as `useDaFileUpload`. Alert acknowledge/resolve are single-button mutations.

### 6.4 URL State (Search Params)

Search params are synced to `useDaFilters` store on route entry. When filters change, the URL is updated via `router.navigate({ search: ... })` without full page reload.

**Sync direction:**
1. On route mount: read search params into `useDaFilters` store (URL is source of truth for initial load).
2. On filter change: update URL search params from store (store is source of truth during session).

This enables deep-linking: a URL like `/da/settlement?deliveryDay=2026-09-16&zone=DE_LU` opens the settlement grid with those filters pre-applied.

---

## S7 -- Data Contract

### 7.1 API Client Extension

**File to modify:** `pv-ui/src/api/client.ts`

The existing `apiFetch` function only supports GET requests. DA requires POST (import trigger) and PUT (alert acknowledge/resolve). The client must be extended:

```typescript
export async function apiFetchMutation<T>(
  path: string,
  method: 'POST' | 'PUT' | 'DELETE',
  body: unknown | null,
  tenantId: string,
): Promise<T> {
  const url = new URL(`${BASE_URL}${path}`, window.location.origin);
  url.searchParams.set('tenantId', tenantId);

  const response = await fetch(url.toString(), {
    method,
    headers: {
      'Accept': 'application/json',
      ...(body !== null ? { 'Content-Type': 'application/json' } : {}),
    },
    ...(body !== null ? { body: JSON.stringify(body) } : {}),
  });

  if (!response.ok) {
    let errorBody: unknown = null;
    try { errorBody = await response.json(); } catch { /* ignore */ }
    throw new ApiError(response.status, response.statusText, errorBody);
  }

  return response.json() as Promise<T>;
}
```

A second extension is needed for multipart file upload (the CSV import):

```typescript
export async function apiFetchMultipart<T>(
  path: string,
  formData: FormData,
  tenantId: string,
): Promise<T> {
  const url = new URL(`${BASE_URL}${path}`, window.location.origin);
  url.searchParams.set('tenantId', tenantId);

  const response = await fetch(url.toString(), {
    method: 'POST',
    headers: { 'Accept': 'application/json' },
    body: formData,  // browser sets Content-Type: multipart/form-data with boundary
  });

  if (!response.ok) {
    let errorBody: unknown = null;
    try { errorBody = await response.json(); } catch { /* ignore */ }
    throw new ApiError(response.status, response.statusText, errorBody);
  }

  return response.json() as Promise<T>;
}
```

### 7.2 DA API Functions

**File:** `pv-ui/src/api/daApi.ts`

| Function | Endpoint | Method | Request | Response |
|---|---|---|---|---|
| `fetchDaImportHistory` | `GET /api/da/import` | GET | filters as query params | `AuctionImportSessionDto[]` |
| `fetchDaImportDetail` | `GET /api/da/import/{id}` | GET | -- | `AuctionImportSessionDto` |
| `triggerDaImport` | `POST /api/da/import` | POST | `AuctionResultBatch` body | `AuctionImportSessionDto` |
| `uploadDaImportFile` | `POST /api/da/import/file` | POST (multipart) | `FormData` with `file` field | `AuctionImportSessionDto` |
| `fetchDaSettlement` | `GET /api/da/settlement` | GET | `deliveryDay`, `zone`, `timezone` | `DaSettlementGridDto` |
| `fetchDaNominations` | `GET /api/da/nominations` | GET | `deliveryDay`, `zone`, `bgId?` | `DaNominationGridDto` |
| `fetchDaBalancingGroups` | `GET /api/da/nominations/balancing-groups` | GET | -- | `BalancingGroupDto[]` |
| `fetchDaImbalanceDaily` | `GET /api/da/imbalance/daily` | GET | `deliveryDay`, `bgId` | `DaImbalanceDailyDto` |
| `fetchDaImbalanceMonthly` | `GET /api/da/imbalance/monthly` | GET | `yearMonth`, `bgId` | `DaImbalanceMonthlyDto` |
| `fetchDaFees` | `GET /api/da/fees` | GET | `deliveryDay` | `DaFeeBreakdownDto` |
| `fetchDaKpi` | `GET /api/da/kpi` | GET | `deliveryDay`, `zone` | `DaKpiDto` |
| `fetchDaAlerts` | `GET /api/da/alerts` | GET | filters as query params | `OperationalAlertDto[]` |
| `fetchDaAlertCounts` | `GET /api/da/alerts/counts` | GET | `status?` | `Record<string, number>` |
| `acknowledgeDaAlert` | `PUT /api/da/alerts/{id}/ack` | PUT | -- | `OperationalAlertDto` |
| `resolveDaAlert` | `PUT /api/da/alerts/{id}/resolve` | PUT | -- | `OperationalAlertDto` |

All GET functions follow the existing pattern: `apiFetch<unknown>(path, params, tenantId)` followed by Zod schema validation. POST/PUT functions use the new `apiFetchMutation`.

### 7.3 Zod Schemas

**File:** `pv-ui/src/schemas/daApi.ts`

All schemas are specified in S16.2.3 of the backend tech spec. They follow the existing patterns from `pv-ui/src/schemas/api.ts`:
- `bigDecimalValue` for required decimal fields
- `bigDecimalNullable` for optional decimal fields
- `instantString` for UTC timestamps
- `z.infer<typeof ...>` for TypeScript type generation

Schemas to define (in order of dependency):

1. `auctionImportSessionSchema` + `AuctionImportSessionDto`
2. `daSettlementRowSchema` + `DaSettlementRowDto`
3. `daSettlementGridSchema` + `DaSettlementGridDto`
4. `nominationComparisonRowSchema` + `NominationComparisonRowDto`
5. `daNominationGridSchema` + `DaNominationGridDto`
6. `imbalanceRowSchema` + `ImbalanceRowDto`
7. `daImbalanceDailySchema` + `DaImbalanceDailyDto`
8. `imbalanceDaySummarySchema` + `ImbalanceDaySummaryDto`
9. `daImbalanceMonthlySchema` + `DaImbalanceMonthlyDto`
10. `feeLineItemSchema` + `FeeLineItemDto`
11. `daFeeBreakdownSchema` + `DaFeeBreakdownDto`
12. `operationalAlertSchema` + `OperationalAlertDto`
13. `daKpiSchema` + `DaKpiDto`
14. `balancingGroupSchema` + `BalancingGroupDto`

The `bigDecimalValue` and `bigDecimalNullable` helpers are re-exported from the existing `api.ts` or imported directly. The `instantString` alias is shared.

### 7.4 Optimistic Updates

**File upload mutation:**

| Hook | Endpoint | On Success |
|---|---|---|
| `useDaFileUpload` | `POST /api/da/import/file` | Invalidate `daKeys.importHistory`. Navigate to import detail view for the new session. |

No optimistic updates in DA v1. Alert acknowledge/resolve mutations invalidate after success. Import trigger navigates to detail view and relies on polling.

---

## S8 -- Real-time and Bitemporal

### 8.1 Live Subscription Integration

DA reuses the existing SSE endpoint (`/api/dashboard/events`) and `useRealtimeInvalidation` hook. Three new change types are added to the invalidation map (see S6.1.3).

The SSE endpoint in `pv-app` must be extended to emit `DA_IMPORT_COMPLETED`, `DA_ALERT_RAISED`, and `DA_SETTLEMENT_UPDATED` events. This is a backend (`pv-app`) change, not a UI change. The UI change is limited to adding the new change types to the `ChangeType` union and `INVALIDATION_MAP`.

**Fallback polling:** All DA queries have `refetchInterval` configured (see S6.1.2). When SSE is disconnected, polling provides data freshness. When SSE reconnects, the invalidation events preempt the polling intervals.

### 8.2 As-Of Toggle

DA data is not bitemporal in v1. Settlement cells are bitemporal (inherited from S5a), but the DA-specific entities (import sessions, nominations, imbalance records, alerts) are not. The as-of toggle in the header remains disabled for DA pages. If future DA entities become bitemporal, the toggle would need to thread `knowledgeTime`/`businessTime` into the DA query keys -- the same pattern already established in `useAsOfClock`.

### 8.3 Import Detail Polling

The `useDaImportDetail` hook uses a dynamic `refetchInterval` that stops when the import reaches a terminal state:

```typescript
refetchInterval: (query) => {
  const status = query.state.data?.status;
  const terminal = ['IMPORTED', 'VALIDATION_FAILED', 'IMPORT_FAILED'];
  if (status && terminal.includes(status)) return false;
  return 2000; // 2 seconds
}
```

### 8.4 Staleness Indicators

The existing `ConnectionStatusIndicator` in the dashboard header applies to DA pages as well (it is in the `AppShell`, not in `DashboardPage`). No additional staleness indicators are needed for DA v1.

---

## S8a -- Performance

### 8a.1 Pagination

DA grids are not expected to exceed 100 rows (max 100 intervals per day for fall-back). No server-side pagination is needed for interval grids.

The import history table may grow over time. For v1, the backend returns all sessions matching the filter. If this exceeds 500 rows, cursor-based pagination should be added. For v1, client-side sorting and filtering is acceptable.

The alerts list uses `offset`/`limit` query params. The UI should use TanStack Query's `useInfiniteQuery` for pagination if the alert count exceeds the default page size. For v1, a fixed page size of 50 with "Load more" button is acceptable.

### 8a.2 Virtualization

Interval grids (settlement, nominations, imbalance daily) have a known maximum of 100 rows. Virtualization is not strictly required but should be applied for consistency with the existing codebase pattern. Use `@tanstack/react-virtual` with 24px row height, overscan 5.

Import history table: virtualize if > 50 rows.

### 8a.3 Lazy Loading

No hierarchical drill-down in DA v1. Each page fetches its own data independently.

### 8a.4 Bundle Splitting

The entire `components/da/` directory should be lazy-loaded via `React.lazy()` on the `daLayoutRoute`. The DA section is a distinct feature that many users may not visit. The initial dashboard load should not include DA components.

```typescript
const DaLayout = React.lazy(() =>
  import('@/components/da/DaLayout').then(m => ({ default: m.DaLayout }))
);
```

### 8a.5 Memoization

- `DaSettlementGrid`: memoize the DST separator insertion logic (iterating rows to detect timezone abbreviation changes). Dependency: `rows` array reference.
- `DaKpiStrip`: memoize color logic for each tile. Dependency: `kpiData` reference.
- `DaNominationGrid`: memoize gate closure countdown computation. Dependency: `deliveryDay`, current time (updated per minute).
- `CategoryBadgeStrip`: memoize total count computation. Dependency: `categories` array.

---

## S8b -- DST Handling

### 8b.1 Interval Grid Rendering

On DST transition days, the settlement grid renders the correct number of intervals:
- **Spring-forward (92 intervals, 23-hour day):** Grid skips from `01:45 CET` to `03:00 CEST`. A `DstSeparatorRow` is inserted between these rows. The grid `aria-label` includes "23-hour delivery day (DST spring-forward)".
- **Fall-back (100 intervals, 25-hour day):** Grid shows two distinct hours: `02:00 CEST` through `02:45 CEST`, then a `DstSeparatorRow`, then `02:00 CET` through `02:45 CET`. The grid `aria-label` includes "25-hour delivery day (DST fall-back)".
- **Normal (96 intervals, 24-hour day):** No separator row.

### 8b.2 DST Detection Algorithm

For spring-forward: iterate sorted rows. When `formatIntervalTime(row.intervalStart).local` shows hour jumping from `01:xx` to `03:xx`, insert separator.

For fall-back: iterate sorted rows. When the timezone abbreviation in `formatIntervalTime(row.intervalStart).local` changes from `CEST` to `CET` within the `02:xx` hour range, insert separator.

Both use the existing `formatIntervalTime()` from `pv-ui/src/lib/dateUtils.ts` and `getDstInfo()` for the header label.

### 8b.3 Date Pickers and UTC Conversion

The delivery day date pickers send local dates (`YYYY-MM-DD`) directly to the backend. The backend DA endpoints accept `deliveryDay` as a local date string (not a UTC instant), unlike the existing dashboard endpoints which accept UTC boundaries. This is a simpler contract: the backend handles the UTC conversion internally.

No `localDateToUtcBoundary()` conversion is needed for DA date pickers.

### 8b.4 Gate Closure Display

The nomination page shows gate closure status in both local and UTC:
- `Gate closure: 14:30 CET (13:30 UTC)` in winter
- `Gate closure: 14:30 CEST (12:30 UTC)` in summer

For v1, the gate closure time (14:30 CET/CEST) is hardcoded (backend spec UI-OI-4). The display uses `formatIntervalTime()` to show dual-clock format.

---

## S9 -- Accessibility

### 9.1 Keyboard Flow

**Tab order within DaLayout:**
1. DA nav tabs (roving tabindex within tablist)
2. KPI strip tiles (left to right)
3. Filter bar inputs (left to right)
4. Grid content (arrow key navigation within grid)
5. Summary bar
6. Action buttons (in detail panels)

**Escape cascade:**
- In detail panel: close panel, return focus to triggering row
- In expanded alert: collapse alert detail
- In grid: clear selection

### 9.2 Screen Reader Labels

| Element | `aria-label` / `aria-labelledby` |
|---|---|
| DA nav tabs | `aria-label="Day-Ahead navigation"` |
| KPI strip | `aria-label="Day-Ahead KPI summary"` |
| Settlement grid | `aria-label="DA settlement intervals for {date}, {zone}"` + DST note |
| Nomination grid | `aria-label="Nomination comparison for {date}"` |
| Imbalance grid | `aria-label="Imbalance settlement for {date}"` |
| Alert list | `aria-label="Operational alerts"` |
| Import history | `aria-label="Import session history"` |
| Each alert row | `aria-label="{severity}: {message}, {date}, {zone}, {status}"` |
| Deviation cell | `aria-label="Deviation: {sign} {value} MW, {severity}"` |
| Progress stepper | `aria-valuetext="{currentStep}"` |
| Gate closure banner | `role="alert"` |
| Status change announcement | `aria-live="polite"` region |

### 9.3 Color Independence

All color-coded indicators have text/icon alternatives (enumerated in S16.8.3 of the backend spec). Key additions for DA:
- Negative price: amber color + "(neg)" tooltip text
- BUY/SELL direction: green/red + text label "BUY"/"SELL"
- Alert severity: red/amber/blue + text "CRIT"/"WARN"/"INFO"
- Deviation: amber/red + numeric magnitude always shown
- Import success/failure: green/red + "IMPORTED"/"FAILED" text + checkmark/X icon

### 9.4 Contrast Verification

All new color combinations must be verified against WCAG 2.2 AA (4.5:1 for text, 3:1 for UI components) in both light and dark themes. The existing token system (`tokens.css`) provides theme-aware colors. New direct Tailwind color classes (e.g., `bg-red-600 dark:bg-red-500`) must be verified against white text.

---

## S10 -- Testing

### 10.1 Unit Tests (Vitest + React Testing Library)

| Test File | Component | What It Tests |
|---|---|---|
| `ProgressStepper.test.tsx` | `ProgressStepper` | Renders correct step states (completed, current, future, error). ARIA progressbar attributes. Step count matches. |
| `DeviationCell.test.tsx` | `DeviationCell` | Threshold color classes for 0, minor (0.1-5.0), significant (>5.0) values. ARIA label includes severity word. Null value renders em-dash. |
| `CategoryBadgeStrip.test.tsx` | `CategoryBadgeStrip` | Renders badge counts. Active badge has pressed style. Zero-count badges dimmed. Keyboard arrow navigation. Click callback fires with category key. "All" badge prepended. |
| `DstSeparatorRow.test.tsx` | `DstSeparatorRow` | Correct text for spring-forward ("02:00-03:00 CET skipped") and fall-back ("02:00-03:00 repeated"). `role="presentation"`. Column span matches `columnCount`. |
| `DaStatusBadge.test.tsx` | `DaStatusBadge` | All 18 status variants render correct label, icon, and background class. Size variants. ARIA label present. |
| `DaSettlementGrid.test.tsx` | `DaSettlementGrid` | Renders 96 rows for normal day. Renders 92 rows + DST separator for spring-forward (92 `intervalCount`). Renders 100 rows + DST separator for fall-back (100 `intervalCount`). Negative price styling (amber class). BUY with negative price shows "CR" suffix. Multiple trades per interval grouped. |
| `DaNominationGrid.test.tsx` | `DaNominationGrid` | Deviation coloring thresholds match `DeviationCell`. Missing nomination shows em-dash. Gate closure banner appears when `nominationSubmitted === false`. |
| `DaImbalanceDailyGrid.test.tsx` | `DaImbalanceDailyGrid` | Over-delivery (positive imbalance) green. Under-delivery (negative) red. `showSign` renders +/- prefix. |
| `DaAlertList.test.tsx` | `DaAlertList` | Arrow key navigation moves focus between rows. Enter expands/collapses detail. A key triggers acknowledge callback on OPEN alert. R key triggers resolve callback on ACKNOWLEDGED alert. ARIA live region announces status change. Escape collapses. |
| `DaKpiStrip.test.tsx` | `DaKpiStrip` | All 6 tiles render. Net volume color: green for positive, red for negative. Alert count: red when CRITICAL severity. Loading state shows skeleton cards. |
| `DaNavTabs.test.tsx` | `DaNavTabs` | All 6 tabs render. Active tab has `aria-selected="true"`. Arrow key navigation cycles tabs. |

### 10.2 Hook Tests

| Test File | Hook | What It Tests |
|---|---|---|
| `useDaQueries.test.ts` | All DA query hooks | Query key includes tenantId. `useDaImportDetail` polling stops on terminal status. Enabled flag respects tenantId presence. |
| `useDaMutations.test.ts` | All DA mutation hooks | `useDaAlertAcknowledge` invalidates alert and alertCounts keys on success. `useDaImportTrigger` invalidates importHistory on success. Error propagation via `ApiError`. |
| `useDaFilters.test.ts` | `useDaFilters` | Default values. `setDeliveryDay` updates state. `clearAlertFilters` resets alert filters. |

### 10.3 Schema Tests

| Test File | What It Tests |
|---|---|
| `daApi.test.ts` | Each Zod schema parses a valid fixture. Each schema rejects malformed input (missing required field, wrong type). `bigDecimalValue` accepts both string and number. `bigDecimalNullable` accepts null. |

### 10.4 Integration Considerations

UI tests mock the API layer via manual fetch mocks (no MSW in current setup). Zod schema validation at the boundary catches API contract drift at runtime. Backend integration tests (S12 of backend spec) verify the API contract independently.

---

## S11 -- File Inventory

### 11.1 New Files

```
pv-ui/src/
  api/
    daQueryKeys.ts                     -- Query key factory (S6.1.1)
    daApi.ts                           -- Fetch functions for all DA endpoints (S7.2)
  components/
    da/
      DaLayout.tsx                     -- Parent layout with tabs + KPI (S5.2.2)
      DaNavTabs.tsx                    -- Tab navigation (S5.2.3)
      DaKpiStrip.tsx                   -- 6-tile KPI row (S5.2.4)
      DaImportPage.tsx                 -- Import status page (S4.2)
      DaFileUploadArea.tsx             -- CSV drag-and-drop upload area
      DaImportHistoryTable.tsx         -- Import session table (S5.2.5)
      DaImportDetailPanel.tsx          -- Import detail panel (S5.2.6)
      DaSettlementPage.tsx             -- Settlement page (S4.3)
      DaSettlementFilterBar.tsx        -- Settlement filter bar
      DaSettlementGrid.tsx             -- Interval settlement grid
      DaSettlementSummaryRow.tsx       -- Settlement totals footer
      DaNominationPage.tsx             -- Nomination page (S4.4)
      DaNominationFilterBar.tsx        -- Nomination filter bar
      DaNominationGrid.tsx             -- Traded vs nominated grid
      DaNominationSummaryBar.tsx       -- Nomination totals
      DaImbalancePage.tsx              -- Imbalance page (S4.5)
      DaImbalanceDailyGrid.tsx         -- Daily interval imbalance grid
      DaImbalanceMonthlyGrid.tsx       -- Monthly aggregation grid
      DaImbalanceSummaryBar.tsx        -- Imbalance totals
      DaFeesPage.tsx                   -- Fees page (S4.6)
      DaFeeBreakdownTable.tsx          -- Fee line items table
      DaAlertsPage.tsx                 -- Alerts page (S4.7)
      DaAlertFilterBar.tsx             -- Alert filter bar
      DaAlertList.tsx                  -- Alert list container
      DaAlertRow.tsx                   -- Expandable alert row
      DaStatusBadge.tsx                -- DA-specific status badge (S5.2.1)
    layout/
      SidebarSection.tsx               -- Collapsible nav section (S3.1.5)
      SidebarLink.tsx                  -- Nav link with icon, badge, active state (S3.1.5)
      SidebarIcons.tsx                 -- SVG icon definitions for sidebar (S3.1.5)
      AlertBadge.tsx                   -- Live alert count badge (S3.1.5)
    primitives/
      ProgressStepper.tsx              -- Multi-step progress indicator (S5.1.1)
      ProgressStepper.test.tsx
      DeviationCell.tsx                -- Threshold-colored numeric cell (S5.1.2)
      DeviationCell.test.tsx
      CategoryBadgeStrip.tsx           -- Category filter badges (S5.1.3)
      CategoryBadgeStrip.test.tsx
      DstSeparatorRow.tsx              -- DST transition marker (S5.1.4)
      DstSeparatorRow.test.tsx
  hooks/
    useDaQueries.ts                    -- TanStack Query hooks (S6.1)
    useDaQueries.test.ts
    useDaMutations.ts                  -- Mutation hooks (S7.2)
    useDaMutations.test.ts
    useDaFilters.ts                    -- Filter state store (S6.2.1)
    useDaFilters.test.ts
    useDaSelection.ts                  -- Selection state store (S6.2.2)
  schemas/
    daApi.ts                           -- Zod schemas for DA responses (S7.3)
    daApi.test.ts
```

**Total new files: 45** (29 components incl. 4 sidebar sub-components, 6 hooks/stores, 4 API/schema modules, 6 test files for primitives/hooks/schemas).

DA page-level component tests (`DaSettlementGrid.test.tsx`, `DaAlertList.test.tsx`, etc.) are co-located:

```
  components/da/
    DaSettlementGrid.test.tsx
    DaNominationGrid.test.tsx
    DaImbalanceDailyGrid.test.tsx
    DaAlertList.test.tsx
    DaKpiStrip.test.tsx
    DaNavTabs.test.tsx
    DaStatusBadge.test.tsx
    DaImportHistoryTable.test.tsx
```

**Total with DA component tests: 53 new files.**

### 11.2 Existing Files to Modify

| File | Change | Rationale |
|---|---|---|
| `pv-ui/src/routeTree.tsx` | Add DA layout route and 7 child routes. Import `DaLayout` via `React.lazy()`. | S16.6 route tree amendment |
| `pv-ui/src/api/client.ts` | Add `apiFetchMutation` function for POST/PUT and `apiFetchMultipart` for file upload. | DA mutations require non-GET methods; CSV import requires multipart |
| `pv-ui/src/hooks/useRealtimeInvalidation.ts` | Add 3 new `ChangeType` values and `INVALIDATION_MAP` entries. | S16.5.4 SSE extension |
| `pv-ui/src/components/layout/Sidebar.tsx` | **Full rewrite.** Replace flat portfolio list with persona-aware, section-grouped, collapsible sidebar (S3.1). Sections: Dashboards, Instruments, Operations. | S3.1 sidebar redesign |
| `pv-ui/src/hooks/useUserPreferences.ts` | Add `sidebarCollapsed: boolean` and `sidebarExpanded: Set<string>` fields for sidebar state persistence. | S3.1 sidebar collapse + section expand state |
| `pv-ui/src/components/layout/AppShell.tsx` | Update sidebar width handling for collapsed/expanded transitions. Add responsive drawer logic for tablet/mobile. | S3.1.6 responsive sidebar |
| `pv-ui/src/styles/tokens.css` | Add DA-specific CSS custom properties if needed (e.g., `--color-alert-critical`). | May not be needed if existing tokens suffice |
| `pv-ui/vite.config.ts` | Add proxy rule for `/api/da/*` SSE endpoints (if separate from `/api/dashboard/events`). | SSE proxy buffering prevention |

---

## S12 -- Implementation Order

The implementation should proceed in layers, each testable before moving to the next:

### Phase 1: Foundation (no visual output yet)
1. `pv-ui/src/schemas/daApi.ts` -- Zod schemas
2. `pv-ui/src/api/client.ts` -- add `apiFetchMutation`
3. `pv-ui/src/api/daQueryKeys.ts` -- query key factory
4. `pv-ui/src/api/daApi.ts` -- fetch functions
5. `pv-ui/src/hooks/useDaFilters.ts` -- filter store
6. `pv-ui/src/hooks/useDaSelection.ts` -- selection store
7. `pv-ui/src/hooks/useDaQueries.ts` -- query hooks
8. `pv-ui/src/hooks/useDaMutations.ts` -- mutation hooks
9. `pv-ui/src/hooks/useRealtimeInvalidation.ts` -- SSE extension
10. Schema and hook tests

### Phase 2: Primitives
1. `ProgressStepper` + test
2. `DeviationCell` + test
3. `CategoryBadgeStrip` + test
4. `DstSeparatorRow` + test
5. `DaStatusBadge` + test

### Phase 3: Sidebar Redesign + Layout and Routing
1. `SidebarIcons`, `SidebarLink`, `SidebarSection`, `AlertBadge` sub-components
2. `Sidebar.tsx` full rewrite (persona-aware sections, collapsible, alert badge)
3. `AppShell.tsx` update (collapsed width handling, responsive breakpoints)
4. `useUserPreferences.ts` update (sidebar state fields)
5. `DaLayout` (with `DaNavTabs` and `DaKpiStrip`)
6. Route tree amendments
7. Verify navigation works end-to-end (all sidebar links, collapse/expand, keyboard)

### Phase 4: Pages (in order of complexity)
1. `DaFeesPage` -- simplest (static table)
2. `DaImportPage` -- moderate (table + detail panel + polling)
3. `DaSettlementPage` -- moderate (interval grid + DST handling)
4. `DaNominationPage` -- moderate (comparison grid + gate closure banner)
5. `DaImbalancePage` -- moderate (daily/monthly toggle + summary)
6. `DaAlertsPage` -- most complex (keyboard shortcuts + mutations + badge strip)

### Phase 5: Integration Testing
1. Component tests for each DA page
2. Verify end-to-end with running `pv-app` backend

---

## S13 -- Open Items

| # | Item | Status | Blocking? | Owner |
|---|---|---|---|---|
| UI-OI-1 | ~~Import trigger UI (file upload vs REST).~~ **Resolved:** Backend `POST /api/da/import/file` (multipart) is implemented. UI includes `DaFileUploadArea` with drag-and-drop CSV upload. | Resolved | No | -- |
| UI-OI-2 | Imbalance "Show original" toggle. Requires `recordVersion` query param on `GET /api/da/imbalance/daily`. | Open -- backend endpoint needs param | Yes for Scenario 3 | Solutions Architect |
| UI-OI-3 | Cross-nav from PositionLedger. Requires `instrumentType` on `PositionContributionDto`. | Open -- DTO change needed | Yes for cross-nav | Implementation team |
| UI-OI-4 | Gate closure time source. Hardcoded 14:30 CET D-1 for v1. Should come from API. | Accepted for v1, should-fix | No for v1 | Functional Expert |
| UI-OI-5 | Sidebar navigation structure. **Resolved:** Full sidebar redesign with persona-aware sections (Dashboards, Instruments, Operations), collapsible mode, alert badges, responsive drawer. See S3.1. | Resolved | No | -- |
| UI-OI-6 | i18n layer. The codebase has no i18n setup. All DA strings are hardcoded English. | Deferred | No for v1 | Platform team |
| UI-OI-7 | Storybook setup. No Storybook exists in the codebase. Primitive stories deferred. | Deferred | No | Platform team |
| UI-OI-8 | E2E tests (Playwright). No Playwright config exists. Deferred. | Deferred | No | Platform team |
| UI-OI-9 | The `apiFetch` currently sends `tenantId` as a query param. DA POST/PUT endpoints may expect `tenantId` in a header or in the request body. Confirm with backend. | Open -- confirm | Potentially | Solutions Architect |
| UI-OI-10 | Alert pagination. v1 uses fixed page size of 50. If alert counts grow, `useInfiniteQuery` should be added. | Accepted for v1 | No | -- |

---

## S14 -- Deviations from S16

This section documents where this UI spec deviates from or clarifies the backend tech spec S16.

| # | S16 Reference | This Spec | Rationale |
|---|---|---|---|
| 1 | S16.5.4 specifies narrow invalidation targets per DA event type (e.g., `DA_IMPORT_COMPLETED: ['da', 'import-history', 'import-detail', 'kpi']`) | This spec uses broad `['da']` prefix for all DA events | Simplicity for v1. DA queries are inexpensive. Can be narrowed later if profiling shows excessive refetches. |
| 2 | S16.2.3 shows schemas inline without `export` | This spec places schemas in a separate file `schemas/daApi.ts` with named exports | Follows the existing codebase convention (`schemas/api.ts` is separate from `api/dashboard.ts`) |
| 3 | S16.9 lists test files co-located with components | This spec lists primitive tests co-located with primitives, DA component tests co-located with DA components | Consistent with existing pattern (though the existing codebase has no test files yet -- `dateUtils.test.ts` and `numberUtils.test.ts` are in `lib/`) |
| 4 | S16.6.4 describes cross-navigation from PositionLedger to DA Settlement | This spec marks it as out of scope (UI-OI-3) | Blocked by missing `instrumentType` field on `PositionContributionDto` |
| 5 | S16.3.7 specifies `?` key for shortcut help tooltip | This spec includes it but notes it requires a global keyboard handler that does not exist yet | The existing codebase has no global keyboard shortcut system |
| 6 | S16 does not address `apiFetch` lacking POST/PUT support | This spec adds `apiFetchMutation` to `client.ts` | Required for import trigger, alert acknowledge, alert resolve |
| 7 | S16.1.1 shows `DaSettlementFilterBar` as a child of `DaSettlementPage` but does not mention `SubGranularityToggle` for settlement | This spec omits sub-granularity toggle for DA settlement | DA settlement is always at 15-minute granularity (quarter-hour intervals from the exchange). Unlike the existing dashboard which aggregates to 30m/60m, DA settlement data is inherently 15-minute. |

---

*End of UI Technical Specification -- Day-Ahead Exchange Spot v1.0*

*This spec is ready for review. Approval is required before Phase 2 implementation begins.*
