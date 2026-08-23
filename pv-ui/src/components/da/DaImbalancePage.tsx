import { ErrorBoundary } from '@/components/primitives/ErrorBoundary';
import { DaImbalanceDailyGrid } from './DaImbalanceDailyGrid';
import { DaImbalanceSummaryBar } from './DaImbalanceSummaryBar';
import { DaImbalanceMonthlyGrid } from './DaImbalanceMonthlyGrid';
import {
  useDaImbalanceDaily,
  useDaImbalanceMonthly,
  useDaBalancingGroups,
} from '@/hooks/useDaQueries';
import { useDaFilters } from '@/hooks/useDaFilters';
import { useUserPreferences } from '@/hooks/useUserPreferences';
import { cn } from '@/lib/cn';

/**
 * DA Imbalance page. Supports daily (interval-level) and monthly (day-level) views.
 * Route: /da/imbalance
 */
export function DaImbalancePage() {
  const deliveryDay = useDaFilters((s) => s.deliveryDay);
  const yearMonth = useDaFilters((s) => s.yearMonth);
  const balancingGroupId = useDaFilters((s) => s.balancingGroupId);
  const imbalanceView = useDaFilters((s) => s.imbalanceView);
  const setDeliveryDay = useDaFilters((s) => s.setDeliveryDay);
  const setYearMonth = useDaFilters((s) => s.setYearMonth);
  const setBalancingGroupId = useDaFilters((s) => s.setBalancingGroupId);
  const setImbalanceView = useDaFilters((s) => s.setImbalanceView);
  const timezone = useUserPreferences((s) => s.timezone);

  const bgQuery = useDaBalancingGroups();
  const balancingGroups = bgQuery.data ?? [];

  const dailyQuery = useDaImbalanceDaily(deliveryDay, balancingGroupId);
  const monthlyQuery = useDaImbalanceMonthly(yearMonth, balancingGroupId);

  const activeQuery = imbalanceView === 'daily' ? dailyQuery : monthlyQuery;

  return (
    <div className="space-y-3 p-4">
      <h2 className="text-lg font-semibold text-text-primary">
        Imbalance Settlement
      </h2>

      {/* Filter toolbar */}
      <div
        role="toolbar"
        aria-label="Imbalance filters"
        className={cn(
          'flex flex-wrap items-center gap-3 px-3 py-2',
          'rounded border border-border-default bg-bg-secondary',
        )}
      >
        {/* View toggle */}
        <div className="flex items-center gap-1" role="radiogroup" aria-label="View mode">
          <button
            role="radio"
            aria-checked={imbalanceView === 'daily'}
            onClick={() => setImbalanceView('daily')}
            className={cn(
              'px-2 py-0.5 text-xs rounded',
              imbalanceView === 'daily'
                ? 'bg-interactive-active text-white'
                : 'text-text-secondary hover:bg-bg-hover',
            )}
          >
            Daily
          </button>
          <button
            role="radio"
            aria-checked={imbalanceView === 'monthly'}
            onClick={() => setImbalanceView('monthly')}
            className={cn(
              'px-2 py-0.5 text-xs rounded',
              imbalanceView === 'monthly'
                ? 'bg-interactive-active text-white'
                : 'text-text-secondary hover:bg-bg-hover',
            )}
          >
            Monthly
          </button>
        </div>

        <div className="w-px h-5 bg-border-default" aria-hidden="true" />

        {/* Date picker: day or month depending on view */}
        {imbalanceView === 'daily' ? (
          <label className="inline-flex items-center gap-1.5 text-xs text-text-secondary">
            <span>Delivery Day</span>
            <input
              type="date"
              value={deliveryDay}
              onChange={(e) => setDeliveryDay(e.target.value)}
              className={cn(
                'px-2 py-0.5 text-xs rounded border border-border-default',
                'bg-bg-primary text-text-primary',
                'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus',
              )}
            />
          </label>
        ) : (
          <label className="inline-flex items-center gap-1.5 text-xs text-text-secondary">
            <span>Month</span>
            <input
              type="month"
              value={yearMonth}
              onChange={(e) => setYearMonth(e.target.value)}
              className={cn(
                'px-2 py-0.5 text-xs rounded border border-border-default',
                'bg-bg-primary text-text-primary',
                'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus',
              )}
            />
          </label>
        )}

        <div className="w-px h-5 bg-border-default" aria-hidden="true" />

        {/* Balancing group */}
        <label className="inline-flex items-center gap-1.5 text-xs text-text-secondary">
          <span>Balancing Group</span>
          <select
            value={balancingGroupId ?? '__all__'}
            onChange={(e) =>
              setBalancingGroupId(e.target.value === '__all__' ? null : e.target.value)
            }
            className={cn(
              'px-2 py-0.5 text-xs rounded border border-border-default',
              'bg-bg-primary text-text-primary',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus',
            )}
          >
            <option value="__all__">All BGs</option>
            {balancingGroups.map((bg) => (
              <option key={bg.bgId} value={bg.bgId}>
                {bg.bgCode} ({bg.tsoArea})
              </option>
            ))}
          </select>
        </label>
      </div>

      {/* Error state */}
      {activeQuery.isError && (
        <div role="alert" className="p-4 text-sm text-numeric-negative">
          Failed to load imbalance data. {activeQuery.error?.message}
        </div>
      )}

      {/* Grid content */}
      <ErrorBoundary>
        {imbalanceView === 'daily' ? (
          <section aria-label="Daily Imbalance Grid">
            <DaImbalanceDailyGrid
              data={dailyQuery.data?.rows}
              isLoading={dailyQuery.isLoading}
              timezone={timezone}
            />
          </section>
        ) : (
          <section aria-label="Monthly Imbalance Grid">
            <DaImbalanceMonthlyGrid
              data={monthlyQuery.data?.dailySummaries}
              isLoading={monthlyQuery.isLoading}
              onDayClick={(day) => {
                setDeliveryDay(day);
                setImbalanceView('daily');
              }}
            />
          </section>
        )}
      </ErrorBoundary>

      {/* Summary bar */}
      <ErrorBoundary>
        <DaImbalanceSummaryBar
          view={imbalanceView}
          dailySummary={dailyQuery.data?.summary}
          monthlySummary={monthlyQuery.data?.monthlySummary}
        />
      </ErrorBoundary>
    </div>
  );
}
