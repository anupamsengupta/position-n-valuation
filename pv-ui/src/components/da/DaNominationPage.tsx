import { useMemo } from 'react';
import { ErrorBoundary } from '@/components/primitives/ErrorBoundary';
import { DaNominationFilterBar } from './DaNominationFilterBar';
import { DaNominationGrid } from './DaNominationGrid';
import { DaNominationSummaryBar } from './DaNominationSummaryBar';
import { useDaNominations } from '@/hooks/useDaQueries';
import { useDaFilters } from '@/hooks/useDaFilters';
import { useUserPreferences } from '@/hooks/useUserPreferences';
import { cn } from '@/lib/cn';

/**
 * Compute gate closure CET timestamp for a delivery day.
 * Gate closure is D-1 at 14:30 CET (always CET, not CEST).
 * For simplicity we use Europe/Berlin which is CET/CEST,
 * and fix at 14:30 local time the day before delivery.
 */
function getGateClosureUtc(deliveryDay: string): Date {
  const parts = deliveryDay.split('-');
  const year = Number(parts[0]);
  const month = Number(parts[1]) - 1;
  const day = Number(parts[2]);

  // D-1: subtract one day
  const d1 = new Date(year, month, day - 1);

  // 14:30 CET. CET is UTC+1, CEST is UTC+2.
  // We approximate using Date constructor in local concept then
  // use the timezone offset. For a robust solution we compute UTC
  // by finding 14:30 in Europe/Berlin.
  const formatter = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Europe/Berlin',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });

  // Build a candidate UTC time: 14:30 CET = 13:30 UTC (winter) or 12:30 UTC (summer)
  // We set 13:30 UTC as a guess and refine
  const guessUtc = new Date(Date.UTC(
    d1.getFullYear(),
    d1.getMonth(),
    d1.getDate(),
    13, 30, 0,
  ));

  const formatted = formatter.format(guessUtc);
  const timePart = formatted.split(', ')[1];
  if (!timePart) return guessUtc;

  const hourMinParts = timePart.split(':');
  const localHour = Number(hourMinParts[0] === '24' ? '0' : hourMinParts[0]);
  const localMin = Number(hourMinParts[1]);

  // We want localHour=14, localMin=30
  const hourDiff = localHour - 14;
  const minDiff = localMin - 30;
  const totalDiffMs = (hourDiff * 60 + minDiff) * 60 * 1000;

  return new Date(guessUtc.getTime() - totalDiffMs);
}

function formatCountdown(ms: number): string {
  if (ms <= 0) return 'past';
  const totalMinutes = Math.floor(ms / 60_000);
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours > 0) {
    return `${hours} hour${hours !== 1 ? 's' : ''} ${minutes} minute${minutes !== 1 ? 's' : ''}`;
  }
  return `${minutes} minute${minutes !== 1 ? 's' : ''}`;
}

interface GateClosureBannerProps {
  nominationSubmitted: boolean;
  gateClosureStatus: 'OK' | 'WARNING' | 'CRITICAL' | null;
  deliveryDay: string;
}

function GateClosureBanner({
  nominationSubmitted,
  gateClosureStatus,
  deliveryDay,
}: GateClosureBannerProps) {
  if (nominationSubmitted) return null;

  const gateClosureUtc = useMemo(() => getGateClosureUtc(deliveryDay), [deliveryDay]);
  const now = new Date();
  const remainingMs = gateClosureUtc.getTime() - now.getTime();
  const countdown = formatCountdown(remainingMs);

  const severity = gateClosureStatus ?? 'INFO';

  const severityClasses: Record<string, string> = {
    INFO: 'bg-blue-50 border-blue-300 text-blue-800 dark:bg-blue-950 dark:border-blue-700 dark:text-blue-200',
    WARNING: 'bg-amber-50 border-amber-300 text-amber-800 dark:bg-amber-950 dark:border-amber-700 dark:text-amber-200',
    CRITICAL: 'bg-red-50 border-red-300 text-red-800 dark:bg-red-950 dark:border-red-700 dark:text-red-200',
    OK: 'bg-blue-50 border-blue-300 text-blue-800 dark:bg-blue-950 dark:border-blue-700 dark:text-blue-200',
  };

  const message = remainingMs > 0
    ? `Nominations not yet submitted. Gate closure in ${countdown}.`
    : 'Nominations not yet submitted. Gate closure has passed.';

  return (
    <div
      role="alert"
      className={cn(
        'px-3 py-2 rounded border text-xs font-medium',
        severityClasses[severity],
      )}
    >
      {message}
    </div>
  );
}

/**
 * DaNominationPage -- orchestrates the nomination comparison view.
 * Route: /da/nominations
 *
 * Layout: GateClosureBanner -> FilterBar -> Grid -> SummaryBar
 */
export function DaNominationPage() {
  const deliveryDay = useDaFilters((s) => s.deliveryDay);
  const zone = useDaFilters((s) => s.zone);
  const balancingGroupId = useDaFilters((s) => s.balancingGroupId);
  const timezone = useUserPreferences((s) => s.timezone);

  const nominationsQuery = useDaNominations(deliveryDay, zone, balancingGroupId);
  const gridData = nominationsQuery.data;

  return (
    <div className="space-y-3 p-4">
      <h2 className="text-lg font-semibold text-text-primary">
        Nominations
      </h2>

      {/* Gate closure banner */}
      {gridData && (
        <GateClosureBanner
          nominationSubmitted={gridData.nominationSubmitted}
          gateClosureStatus={gridData.gateClosureStatus}
          deliveryDay={deliveryDay}
        />
      )}

      {/* Filter bar */}
      <ErrorBoundary>
        <DaNominationFilterBar />
      </ErrorBoundary>

      {/* Nomination grid */}
      <ErrorBoundary>
        <section aria-label="Nomination Comparison Grid">
          <DaNominationGrid
            data={gridData?.rows}
            isLoading={nominationsQuery.isLoading}
            timezone={timezone}
            intervalCount={gridData?.intervalCount}
          />
        </section>
      </ErrorBoundary>

      {/* Summary bar */}
      <ErrorBoundary>
        <DaNominationSummaryBar summary={gridData?.summary} />
      </ErrorBoundary>
    </div>
  );
}
