import { ErrorBoundary } from '@/components/primitives/ErrorBoundary';
import { DaSettlementFilterBar } from './DaSettlementFilterBar';
import { DaSettlementGrid } from './DaSettlementGrid';
import { DaSettlementSummaryRow } from './DaSettlementSummaryRow';
import { useDaSettlement } from '@/hooks/useDaQueries';
import { useDaFilters } from '@/hooks/useDaFilters';
import { useUserPreferences } from '@/hooks/useUserPreferences';

/**
 * DA Settlement page. Orchestrates the settlement grid with filters and summary.
 * Route: /da/settlement
 */
export function DaSettlementPage() {
  const deliveryDay = useDaFilters((s) => s.deliveryDay);
  const zone = useDaFilters((s) => s.zone);
  const timezone = useUserPreferences((s) => s.timezone);

  const settlementQuery = useDaSettlement(deliveryDay, zone, timezone);
  const gridData = settlementQuery.data;

  return (
    <div className="space-y-3 p-4">
      <h2 className="text-lg font-semibold text-text-primary">
        Settlement
      </h2>

      {/* Filter bar */}
      <ErrorBoundary>
        <DaSettlementFilterBar />
      </ErrorBoundary>

      {/* Settlement grid */}
      <ErrorBoundary>
        <section aria-label="Settlement Grid">
          <DaSettlementGrid
            data={gridData?.rows}
            isLoading={settlementQuery.isLoading}
            timezone={timezone}
          />
        </section>
      </ErrorBoundary>

      {/* Error state */}
      {settlementQuery.isError && (
        <div role="alert" className="p-4 text-sm text-numeric-negative">
          Failed to load settlement data. {settlementQuery.error?.message}
        </div>
      )}

      {/* Summary row */}
      {gridData?.summary && (
        <ErrorBoundary>
          <DaSettlementSummaryRow summary={gridData.summary} />
        </ErrorBoundary>
      )}
    </div>
  );
}
