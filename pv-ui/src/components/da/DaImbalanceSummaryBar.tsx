import { NumericCell } from '@/components/primitives/NumericCell';
import type { DaImbalanceDailyDto, DaImbalanceMonthlyDto } from '@/schemas/daApi';

export interface DaImbalanceSummaryBarProps {
  view: 'daily' | 'monthly';
  dailySummary: DaImbalanceDailyDto['summary'] | undefined;
  monthlySummary: DaImbalanceMonthlyDto['monthlySummary'] | undefined;
}

/**
 * Summary strip for the imbalance page.
 * Shows different content depending on daily vs monthly view.
 */
export function DaImbalanceSummaryBar({
  view,
  dailySummary,
  monthlySummary,
}: DaImbalanceSummaryBarProps) {
  if (view === 'daily' && !dailySummary) return null;
  if (view === 'monthly' && !monthlySummary) return null;

  return (
    <div
      role="status"
      aria-label="Imbalance summary"
      className="flex flex-wrap items-center gap-6 px-3 py-2 rounded border border-border-default bg-bg-secondary text-xs"
    >
      {view === 'daily' && dailySummary && (
        <>
          <div className="flex items-center gap-1">
            <span className="text-text-secondary">Net Imbalance:</span>
            <NumericCell
              value={dailySummary.netImbalanceMwh}
              precision="MWH"
              showSign
            />
          </div>
          <div className="flex items-center gap-1">
            <span className="text-text-secondary">Net Cost:</span>
            <NumericCell
              value={dailySummary.netImbalanceCost}
              precision="MONETARY"
              currency={dailySummary.currency}
              showSign
            />
          </div>
          <div className="flex items-center gap-1">
            <span className="text-text-secondary">Max Interval Imbalance:</span>
            <NumericCell
              value={dailySummary.maxIntervalImbalanceMw}
              precision="MW"
              showSign
            />
          </div>
        </>
      )}
      {view === 'monthly' && monthlySummary && (
        <>
          <div className="flex items-center gap-1">
            <span className="text-text-secondary">Total Net Imbalance:</span>
            <NumericCell
              value={monthlySummary.totalNetImbalanceMwh}
              precision="MWH"
              showSign
            />
          </div>
          <div className="flex items-center gap-1">
            <span className="text-text-secondary">Total Net Cost:</span>
            <NumericCell
              value={monthlySummary.totalNetCost}
              precision="MONETARY"
              currency={monthlySummary.currency}
              showSign
            />
          </div>
        </>
      )}
    </div>
  );
}
