import { useState, useCallback, useMemo } from 'react';
import { PortfolioSummarySection } from './PortfolioSummarySection';
import { RollupGrid, type RollupGridRow } from './RollupGrid';
import { PositionLedger } from './PositionLedger';
import { IntervalDetailPanel } from './IntervalDetailPanel';
import { GranularityToggle } from '@/components/primitives/GranularityToggle';
import { ErrorBoundary } from '@/components/primitives/ErrorBoundary';
import {
  usePortfolioSummary,
  useRollupGrid,
  usePositionContributions,
} from '@/hooks/useDashboardQueries';
import { useDashboardSelection } from '@/hooks/useDashboardSelection';
import { useUserPreferences } from '@/hooks/useUserPreferences';
import { localDateToUtcBoundary, getDefaultDateRange, formatPeriodLabel } from '@/lib/dateUtils';
import type { TimeGranularity } from '@/schemas/types';
import type { PositionContributionDto } from '@/schemas/api';

export interface DashboardPageProps {
  portfolioId: string;
}

/**
 * Main dashboard page composing L1-L4 with progressive disclosure.
 * Route: /dashboard/:portfolioId
 */
export function DashboardPage({ portfolioId }: DashboardPageProps) {
  const timezone = useUserPreferences((s) => s.timezone);
  const {
    selectedPeriod,
    selectedPositionId,
    setSelectedPeriod,
    setSelectedPosition,
  } = useDashboardSelection();

  const [granularity, setGranularity] = useState<TimeGranularity>('MONTHLY');

  // Date range defaults
  const { rangeStart: defaultStart, rangeEnd: defaultEnd } = useMemo(
    () => getDefaultDateRange(),
    [],
  );

  // Convert local dates to UTC boundaries for API calls
  const rangeStartUtc = useMemo(
    () => localDateToUtcBoundary(defaultStart, timezone),
    [defaultStart, timezone],
  );
  const rangeEndUtc = useMemo(
    () => localDateToUtcBoundary(defaultEnd, timezone),
    [defaultEnd, timezone],
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

  const handlePositionRowClick = useCallback(
    (row: PositionContributionDto) => {
      setSelectedPosition(row.positionId);
    },
    [setSelectedPosition],
  );

  const handlePositionLedgerClose = useCallback(() => {
    setSelectedPeriod(null);
  }, [setSelectedPeriod]);

  const handleIntervalDetailClose = useCallback(() => {
    setSelectedPosition(null);
  }, [setSelectedPosition]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Escape') {
        if (selectedPositionId) {
          setSelectedPosition(null);
        } else if (selectedPeriod) {
          setSelectedPeriod(null);
        }
      }
    },
    [selectedPeriod, selectedPositionId, setSelectedPeriod, setSelectedPosition],
  );

  const periodLabel = selectedPeriod
    ? formatPeriodLabel(selectedPeriod.start, granularity, timezone)
    : '';

  return (
    <div className="space-y-4" onKeyDown={handleKeyDown}>
      {/* Dashboard header with controls */}
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold text-text-primary">
          Position &amp; PnL Dashboard
          <span className="ml-2 text-sm font-normal text-text-muted">{portfolioId}</span>
        </h2>
        <div className="flex items-center gap-3">
          <GranularityToggle value={granularity} onChange={setGranularity} />
        </div>
      </div>

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
            onRowClick={handleRollupRowClick}
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
            selectedPositionId={selectedPositionId}
            onRowClick={handlePositionRowClick}
            onClose={handlePositionLedgerClose}
          />
        </ErrorBoundary>
      )}

      {/* L4: Interval Detail (conditional) */}
      {selectedPeriod && selectedPositionId && (
        <ErrorBoundary>
          <IntervalDetailPanel
            portfolioId={portfolioId}
            periodStart={selectedPeriod.start}
            periodEnd={selectedPeriod.end}
            positionId={selectedPositionId}
            onClose={handleIntervalDetailClose}
          />
        </ErrorBoundary>
      )}
    </div>
  );
}
