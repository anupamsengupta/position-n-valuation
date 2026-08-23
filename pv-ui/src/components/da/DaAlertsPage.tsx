import { useCallback } from 'react';
import { ErrorBoundary } from '@/components/primitives/ErrorBoundary';
import { DaAlertFilterBar } from './DaAlertFilterBar';
import { DaAlertList } from './DaAlertList';
import { useDaAlerts, useDaAlertCounts } from '@/hooks/useDaQueries';
import { useDaFilters } from '@/hooks/useDaFilters';
import { useDaSelection } from '@/hooks/useDaSelection';
import { cn } from '@/lib/cn';
import type { AlertCategory } from '@/schemas/daApi';

const SEVERITY_BADGE: Record<string, string> = {
  CRITICAL: 'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-200',
  WARNING: 'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-200',
  INFO: 'bg-blue-100 text-blue-800 dark:bg-blue-950 dark:text-blue-200',
};

/**
 * DA Alerts page. Displays operational alerts with filtering, severity badges,
 * and expand/collapse for alert detail.
 * Route: /da/alerts
 */
export function DaAlertsPage() {
  const alertFilters = useDaFilters((s) => s.getAlertFilters)();
  const clearAlertFilters = useDaFilters((s) => s.clearAlertFilters);
  const expandedAlertId = useDaSelection((s) => s.expandedAlertId);
  const setExpandedAlert = useDaSelection((s) => s.setExpandedAlert);

  const alertsQuery = useDaAlerts(alertFilters);
  const countsQuery = useDaAlertCounts('OPEN');

  const handleToggleExpand = useCallback(
    (alertId: string) => {
      setExpandedAlert(expandedAlertId === alertId ? null : alertId);
    },
    [expandedAlertId, setExpandedAlert],
  );

  const counts = countsQuery.data as Record<string, number> | undefined;
  const totalOpen = counts
    ? Object.values(counts).reduce((sum, n) => sum + n, 0)
    : 0;

  return (
    <div className="space-y-3 p-4">
      {/* Header with open count */}
      <div className="flex items-center gap-3">
        <h2 className="text-lg font-semibold text-text-primary">
          Operational Alerts
        </h2>
        {totalOpen > 0 && (
          <span
            className={cn(
              'inline-flex items-center px-2 py-0.5 text-xs font-medium rounded-full',
              SEVERITY_BADGE['CRITICAL'],
            )}
          >
            {totalOpen} open
          </span>
        )}
      </div>

      {/* Category count strip */}
      {counts && Object.keys(counts).length > 0 && (
        <div className="flex flex-wrap gap-2">
          {Object.entries(counts).map(([category, count]) => (
            <span
              key={category}
              className={cn(
                'inline-flex items-center gap-1 px-2 py-0.5 text-xs rounded',
                'border border-border-default bg-bg-secondary text-text-secondary',
              )}
            >
              {formatCategory(category as AlertCategory)}
              <span className="font-semibold text-text-primary">{count}</span>
            </span>
          ))}
        </div>
      )}

      {/* Filter bar */}
      <ErrorBoundary>
        <DaAlertFilterBar onClear={clearAlertFilters} />
      </ErrorBoundary>

      {/* Error state */}
      {alertsQuery.isError && (
        <div role="alert" className="p-4 text-sm text-numeric-negative">
          Failed to load alerts. {alertsQuery.error?.message}
        </div>
      )}

      {/* Alert list */}
      <ErrorBoundary>
        <section aria-label="Alerts list">
          <DaAlertList
            alerts={alertsQuery.data}
            isLoading={alertsQuery.isLoading}
            expandedAlertId={expandedAlertId}
            onToggleExpand={handleToggleExpand}
          />
        </section>
      </ErrorBoundary>
    </div>
  );
}

function formatCategory(category: AlertCategory): string {
  return category
    .replace(/_/g, ' ')
    .replace(/\b\w/g, (c) => c.toUpperCase());
}
