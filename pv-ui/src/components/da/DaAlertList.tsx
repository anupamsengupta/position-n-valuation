import { SkeletonTable } from '@/components/primitives/SkeletonRow';
import { EmptyState } from '@/components/primitives/EmptyState';
import { cn } from '@/lib/cn';
import type { OperationalAlertDto } from '@/schemas/daApi';

export interface DaAlertListProps {
  alerts: OperationalAlertDto[] | undefined;
  isLoading: boolean;
  expandedAlertId: string | null;
  onToggleExpand: (alertId: string) => void;
}

const SEVERITY_DOT: Record<string, string> = {
  CRITICAL: 'bg-red-500',
  WARNING: 'bg-amber-500',
  INFO: 'bg-blue-500',
};

const STATUS_BADGE: Record<string, string> = {
  OPEN: 'bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-300',
  ACKNOWLEDGED: 'bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-300',
  RESOLVED: 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-300',
};

function formatTimestamp(iso: string): string {
  try {
    return new Intl.DateTimeFormat('en-GB', {
      dateStyle: 'short',
      timeStyle: 'medium',
      timeZone: 'Europe/Berlin',
    }).format(new Date(iso));
  } catch {
    return iso;
  }
}

/**
 * Expandable alert list. Each alert shows severity dot, message, status,
 * and timestamp. Expanding reveals full detail.
 */
export function DaAlertList({
  alerts,
  isLoading,
  expandedAlertId,
  onToggleExpand,
}: DaAlertListProps) {
  if (isLoading) {
    return <SkeletonTable rows={6} columns={[5, 40, 15, 20]} />;
  }

  if (!alerts || alerts.length === 0) {
    return <EmptyState message="No alerts match the current filters." />;
  }

  return (
    <ul className="space-y-1" role="list">
      {alerts.map((alert) => {
        const isExpanded = expandedAlertId === alert.alertId;
        return (
          <li
            key={alert.alertId}
            className={cn(
              'border border-border-default rounded',
              isExpanded && 'bg-bg-secondary',
            )}
          >
            {/* Summary row */}
            <button
              onClick={() => onToggleExpand(alert.alertId)}
              aria-expanded={isExpanded}
              className={cn(
                'w-full flex items-center gap-3 px-3 py-2 text-left',
                'hover:bg-bg-hover transition-colors',
              )}
            >
              {/* Severity dot */}
              <span
                className={cn(
                  'w-2 h-2 rounded-full flex-shrink-0',
                  SEVERITY_DOT[alert.severity] ?? 'bg-gray-400',
                )}
                aria-label={`Severity: ${alert.severity}`}
              />

              {/* Message */}
              <span className="flex-1 text-xs text-text-primary truncate">
                {alert.message}
              </span>

              {/* Status badge */}
              <span
                className={cn(
                  'px-1.5 py-0.5 text-[10px] font-medium rounded',
                  STATUS_BADGE[alert.status] ?? '',
                )}
              >
                {alert.status}
              </span>

              {/* Timestamp */}
              <span className="text-[10px] text-text-muted whitespace-nowrap">
                {formatTimestamp(alert.raisedAt)}
              </span>

              {/* Expand indicator */}
              <span
                className={cn(
                  'text-text-muted transition-transform',
                  isExpanded && 'rotate-180',
                )}
                aria-hidden="true"
              >
                &#9662;
              </span>
            </button>

            {/* Detail panel */}
            {isExpanded && (
              <div className="px-3 pb-3 pt-1 border-t border-border-default">
                <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-xs">
                  <dt className="text-text-muted">Alert ID</dt>
                  <dd className="text-text-primary font-mono text-[10px]">
                    {alert.alertId}
                  </dd>

                  <dt className="text-text-muted">Category</dt>
                  <dd className="text-text-primary">
                    {alert.category.replace(/_/g, ' ')}
                  </dd>

                  <dt className="text-text-muted">Severity</dt>
                  <dd className="text-text-primary">{alert.severity}</dd>

                  <dt className="text-text-muted">Alert Type</dt>
                  <dd className="text-text-primary">{alert.alertType}</dd>

                  {alert.deliveryDay && (
                    <>
                      <dt className="text-text-muted">Delivery Day</dt>
                      <dd className="text-text-primary">{alert.deliveryDay}</dd>
                    </>
                  )}

                  {alert.biddingZone && (
                    <>
                      <dt className="text-text-muted">Zone</dt>
                      <dd className="text-text-primary">{alert.biddingZone}</dd>
                    </>
                  )}

                  <dt className="text-text-muted">Raised At</dt>
                  <dd className="text-text-primary">
                    {formatTimestamp(alert.raisedAt)}
                  </dd>

                  {alert.acknowledgedBy && (
                    <>
                      <dt className="text-text-muted">Acknowledged By</dt>
                      <dd className="text-text-primary">
                        {alert.acknowledgedBy}{' '}
                        {alert.acknowledgedAt && (
                          <span className="text-text-muted">
                            at {formatTimestamp(alert.acknowledgedAt)}
                          </span>
                        )}
                      </dd>
                    </>
                  )}

                  {alert.resolvedAt && (
                    <>
                      <dt className="text-text-muted">Resolved At</dt>
                      <dd className="text-text-primary">
                        {formatTimestamp(alert.resolvedAt)}
                      </dd>
                    </>
                  )}

                  {alert.sourceEventId && (
                    <>
                      <dt className="text-text-muted">Source Event</dt>
                      <dd className="text-text-primary font-mono text-[10px]">
                        {alert.sourceEventId}
                      </dd>
                    </>
                  )}
                </dl>
              </div>
            )}
          </li>
        );
      })}
    </ul>
  );
}
