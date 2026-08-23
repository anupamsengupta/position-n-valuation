import { useCallback, useMemo, useEffect, useRef } from 'react';
import { useNavigate } from '@tanstack/react-router';
import { PortfolioSummarySection } from './PortfolioSummarySection';
import { PortfolioCardStrip } from './PortfolioCardStrip';
import { RollupGrid, type RollupGridRow } from './RollupGrid';
import { PositionLedger } from './PositionLedger';
import { IntervalDetailPanel } from './IntervalDetailPanel';
import { DashboardFilterBar } from './DashboardFilterBar';
import { ConnectionStatusIndicator } from './ConnectionStatusIndicator';
import { ErrorBoundary } from '@/components/primitives/ErrorBoundary';
import {
  usePortfolioSummary,
  useRollupGrid,
  usePositionContributions,
} from '@/hooks/useDashboardQueries';
import { useDashboardSelection } from '@/hooks/useDashboardSelection';
import { useDashboardFilters } from '@/hooks/useDashboardFilters';
import { useUserPreferences } from '@/hooks/useUserPreferences';
import { useRealtimeInvalidation } from '@/hooks/useRealtimeInvalidation';
import { localDateToUtcBoundary, nextDay, formatPeriodLabel } from '@/lib/dateUtils';
import type { PositionContributionDto } from '@/schemas/api';

export interface DashboardPageProps {
  portfolioId: string;
}

/**
 * Main dashboard page composing L1-L4 with progressive disclosure.
 * Route: /dashboard/:portfolioId
 */
export function DashboardPage({ portfolioId }: DashboardPageProps) {
  const navigate = useNavigate();
  const connectionStatus = useRealtimeInvalidation();
  const timezone = useUserPreferences((s) => s.timezone);
  const {
    selectedPeriod,
    selectedRangeStarts,
    selectedPositionIds,
    activatedPositionId,
    setSelectedPeriod,
    extendPeriodRange,
    clearRangeSelection,
    setActivatedPosition,
    setPositionSelection,
    deselectAllPositions,
  } = useDashboardSelection();

  // Read filter state from store
  const granularity = useDashboardFilters((s) => s.granularity);
  const dateRange = useDashboardFilters((s) => s.dateRange);
  const storePortfolioId = useDashboardFilters((s) => s.portfolioId);
  const setPortfolioId = useDashboardFilters((s) => s.setPortfolioId);

  // Sync route param → store (with loop guard)
  const syncRef = useRef(false);
  useEffect(() => {
    if (portfolioId !== storePortfolioId) {
      syncRef.current = true;
      setPortfolioId(portfolioId);
    }
  }, [portfolioId, storePortfolioId, setPortfolioId]);

  // Convert local dates to UTC boundaries for API calls.
  const rangeStartUtc = useMemo(
    () => localDateToUtcBoundary(dateRange.rangeStart, timezone),
    [dateRange.rangeStart, timezone],
  );
  const rangeEndUtc = useMemo(
    () => localDateToUtcBoundary(nextDay(dateRange.rangeEnd), timezone),
    [dateRange.rangeEnd, timezone],
  );

  // L1: Portfolio summary
  const summaryQuery = usePortfolioSummary(
    portfolioId,
    rangeStartUtc,
    rangeEndUtc,
    granularity,
  );

  // L2: Rollup grid
  const rollupQuery = useRollupGrid(
    portfolioId,
    rangeStartUtc,
    rangeEndUtc,
    granularity,
  );

  // L3: Position contributions (only when period is selected)
  const positionsQuery = usePositionContributions(
    portfolioId,
    selectedPeriod?.start,
    selectedPeriod?.end,
  );

  // Derive rollup rows for range extension (must match RollupGrid's derivation)
  const rollupRows = useMemo<RollupGridRow[]>(() => {
    if (!rollupQuery.data) return [];
    return rollupQuery.data.map((cell) => {
      const now = new Date().toISOString();
      let periodStatus: 'SETTLED' | 'TRANSITION' | 'FORWARD';
      if (cell.periodEnd <= now) periodStatus = 'SETTLED';
      else if (cell.periodStart >= now) periodStatus = 'FORWARD';
      else periodStatus = 'TRANSITION';

      const parseVal = (v: string | number | null | undefined) => {
        if (v === null || v === undefined) return null;
        const n = typeof v === 'number' ? v : parseFloat(v);
        return isNaN(n) ? null : n;
      };
      const settled = parseVal(cell.settledValue);
      const forward = parseVal(cell.forwardMarkValue);
      const totalValue =
        settled !== null && forward !== null ? settled + forward : settled ?? forward;
      return { ...cell, periodStatus, totalValue };
    });
  }, [rollupQuery.data]);

  // Compute positionIds array for IntervalDetailPanel
  const effectivePositionIds = useMemo<string[]>(() => {
    if (selectedPositionIds.size >= 2) {
      return Array.from(selectedPositionIds);
    }
    if (selectedPositionIds.size === 1) {
      return Array.from(selectedPositionIds);
    }
    if (activatedPositionId) {
      return [activatedPositionId];
    }
    return [];
  }, [selectedPositionIds, activatedPositionId]);

  // Compute trade IDs for NettedViewBanner display
  const selectedTradeIds = useMemo<string[]>(() => {
    if (!positionsQuery.data || selectedPositionIds.size < 2) return [];
    return positionsQuery.data
      .filter((p) => p.positionId != null && selectedPositionIds.has(p.positionId))
      .map((p) => p.tradeId);
  }, [positionsQuery.data, selectedPositionIds]);

  // Show L4 if any positions are selected or activated
  const showIntervalDetail = selectedPeriod && effectivePositionIds.length > 0;

  // Handlers
  const handleRollupRowClick = useCallback(
    (row: RollupGridRow) => {
      setSelectedPeriod({
        start: row.periodStart,
        end: row.periodEnd,
        status: row.periodStatus,
      });
    },
    [setSelectedPeriod],
  );

  const handleRollupShiftClick = useCallback(
    (row: RollupGridRow) => {
      extendPeriodRange(
        { periodStart: row.periodStart, periodEnd: row.periodEnd, periodStatus: row.periodStatus },
        rollupRows.map((r) => ({
          periodStart: r.periodStart,
          periodEnd: r.periodEnd,
          periodStatus: r.periodStatus,
        })),
      );
    },
    [extendPeriodRange, rollupRows],
  );

  const handlePositionRowActivate = useCallback(
    (row: PositionContributionDto) => {
      setActivatedPosition(row.positionId);
    },
    [setActivatedPosition],
  );

  const handleSelectionChange = useCallback(
    (newSelection: ReadonlySet<string>) => {
      setPositionSelection(newSelection);
    },
    [setPositionSelection],
  );

  const handlePositionLedgerClose = useCallback(() => {
    setSelectedPeriod(null);
  }, [setSelectedPeriod]);

  const handleIntervalDetailClose = useCallback(() => {
    setActivatedPosition(null);
    deselectAllPositions();
  }, [setActivatedPosition, deselectAllPositions]);

  const handleClearPositionSelection = useCallback(() => {
    deselectAllPositions();
  }, [deselectAllPositions]);

  const handlePortfolioSelect = useCallback(
    (id: string) => {
      setPortfolioId(id);
      navigate({ to: '/dashboard/$portfolioId', params: { portfolioId: id } });
    },
    [setPortfolioId, navigate],
  );

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Escape') {
        if (activatedPositionId) {
          setActivatedPosition(null);
        } else if (selectedPositionIds.size > 0) {
          deselectAllPositions();
        } else if (selectedPeriod) {
          setSelectedPeriod(null);
        }
      }
    },
    [selectedPeriod, activatedPositionId, selectedPositionIds.size, setSelectedPeriod, setActivatedPosition, deselectAllPositions],
  );

  const periodLabel = selectedPeriod
    ? selectedRangeStarts.length > 1
      ? `${formatPeriodLabel(selectedPeriod.start, granularity, timezone)} \u2013 ${formatPeriodLabel(selectedRangeStarts[selectedRangeStarts.length - 1]!, granularity, timezone)}`
      : formatPeriodLabel(selectedPeriod.start, granularity, timezone)
    : '';

  return (
    <div className="space-y-4" onKeyDown={handleKeyDown}>
      {/* Dashboard header */}
      <div className="flex items-center gap-3">
        <h2 className="text-lg font-semibold text-text-primary">
          Position &amp; PnL Dashboard
        </h2>
        <ConnectionStatusIndicator status={connectionStatus} />
      </div>

      {/* Filter toolbar */}
      <DashboardFilterBar />

      {/* Portfolio card strip */}
      <ErrorBoundary>
        <PortfolioCardStrip
          selectedPortfolioId={portfolioId}
          rangeStart={rangeStartUtc}
          rangeEnd={rangeEndUtc}
          onSelect={handlePortfolioSelect}
        />
      </ErrorBoundary>

      {/* L1: Portfolio Summary Cards */}
      <ErrorBoundary>
        <PortfolioSummarySection
          data={summaryQuery.data}
          isLoading={summaryQuery.isLoading}
          isError={summaryQuery.isError}
          error={summaryQuery.error}
        />
      </ErrorBoundary>

      {/* L2: Rollup Grid */}
      <ErrorBoundary>
        <section aria-label="Rollup Grid">
          <RollupGrid
            data={rollupQuery.data}
            isLoading={rollupQuery.isLoading}
            granularity={granularity}
            timezone={timezone}
            selectedPeriodStart={selectedPeriod?.start ?? null}
            selectedRangeStarts={selectedRangeStarts}
            onRowClick={handleRollupRowClick}
            onRowShiftClick={handleRollupShiftClick}
            onClearRange={clearRangeSelection}
          />
        </section>
      </ErrorBoundary>

      {/* L3: Position Ledger (conditional) */}
      {selectedPeriod && (
        <ErrorBoundary>
          <PositionLedger
            data={positionsQuery.data}
            isLoading={positionsQuery.isLoading}
            periodLabel={periodLabel}
            periodStatus={selectedPeriod.status}
            selectedPositionIds={selectedPositionIds}
            activatedPositionId={activatedPositionId}
            onRowActivate={handlePositionRowActivate}
            onSelectionChange={handleSelectionChange}
            onClose={handlePositionLedgerClose}
          />
        </ErrorBoundary>
      )}

      {/* L4: Interval Detail (conditional) */}
      {showIntervalDetail && (
        <ErrorBoundary>
          <IntervalDetailPanel
            portfolioId={portfolioId}
            periodStart={selectedPeriod.start}
            periodEnd={selectedPeriod.end}
            positionIds={effectivePositionIds}
            selectedTradeIds={selectedTradeIds}
            onClose={handleIntervalDetailClose}
            onClearSelection={handleClearPositionSelection}
          />
        </ErrorBoundary>
      )}
    </div>
  );
}
