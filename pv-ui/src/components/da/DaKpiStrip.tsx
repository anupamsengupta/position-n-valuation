import { KpiTile } from '@/components/primitives/KpiTile';
import { useDaKpi } from '@/hooks/useDaQueries';
import { useDaFilters } from '@/hooks/useDaFilters';
import { cn } from '@/lib/cn';

/**
 * KPI strip for the Day-Ahead layout header.
 * Shows 6 tiles: Net Volume, VWAP, Settlement, Fees, Imbalance, Alerts.
 * Data is fetched via useDaKpi using the current delivery day and zone from filters.
 */
export function DaKpiStrip() {
  const deliveryDay = useDaFilters((s) => s.deliveryDay);
  const zone = useDaFilters((s) => s.zone);
  const { data: kpi, isLoading } = useDaKpi(deliveryDay, zone);

  if (isLoading) {
    return (
      <div
        className="grid grid-cols-6 gap-4 px-4 py-3 border-b border-border-default bg-bg-secondary"
        aria-busy="true"
      >
        {Array.from({ length: 6 }).map((_, i) => (
          <div key={i} className="flex flex-col gap-1">
            <div className="h-3 w-16 bg-bg-tertiary rounded animate-pulse" />
            <div className="h-6 w-24 bg-bg-tertiary rounded animate-pulse" />
          </div>
        ))}
      </div>
    );
  }

  if (!kpi) {
    return null;
  }

  // Determine alert severity color class
  const alertColorClass =
    kpi.maxAlertSeverity === 'CRITICAL'
      ? 'text-status-error'
      : kpi.maxAlertSeverity === 'WARNING'
        ? 'text-status-transition'
        : '';

  return (
    <div className="grid grid-cols-6 gap-4 px-4 py-3 border-b border-border-default bg-bg-secondary">
      <KpiTile
        label="Net Volume"
        value={kpi.netVolumeMwh}
        precision="MONETARY"
        secondaryLabel="MWh"
      />
      <KpiTile
        label="VWAP"
        value={kpi.vwap}
        precision="PRICE"
        currency={kpi.currency}
        secondaryLabel={`${kpi.currency}/MWh`}
      />
      <KpiTile
        label="Settlement"
        value={kpi.settlementTotal}
        precision="MONETARY"
        currency={kpi.currency}
      />
      <KpiTile
        label="Fees"
        value={kpi.exchangeFees}
        precision="MONETARY"
        currency={kpi.currency}
      />
      <KpiTile
        label="Imbalance"
        value={kpi.imbalanceCost}
        precision="MONETARY"
        currency={kpi.currency}
        trend={
          kpi.imbalanceCost === null
            ? null
            : Number(kpi.imbalanceCost) > 0
              ? 'down'
              : Number(kpi.imbalanceCost) < 0
                ? 'up'
                : 'flat'
        }
      />
      <KpiTile
        label="Alerts"
        value={kpi.openAlertCount}
        precision="MONETARY"
        className={cn(alertColorClass)}
        secondaryLabel={
          kpi.maxAlertSeverity
            ? `Max: ${kpi.maxAlertSeverity}`
            : 'No open alerts'
        }
      />
    </div>
  );
}
