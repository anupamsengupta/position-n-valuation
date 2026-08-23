import { NumericCell } from '@/components/primitives/NumericCell';
import type { DaNominationGridDto } from '@/schemas/daApi';

export interface DaNominationSummaryBarProps {
  summary: DaNominationGridDto['summary'] | undefined;
}

/**
 * Summary strip below the nomination grid showing totals.
 * Displays total traded MWh, total nominated MWh, net deviation MWh,
 * and the count of intervals with deviation.
 */
export function DaNominationSummaryBar({ summary }: DaNominationSummaryBarProps) {
  if (!summary) {
    return null;
  }

  return (
    <div
      role="status"
      aria-label="Nomination summary"
      className="flex flex-wrap items-center gap-6 px-3 py-2 rounded border border-border-default bg-bg-secondary text-xs"
    >
      <div className="flex items-center gap-1">
        <span className="text-text-secondary">Traded:</span>
        <NumericCell value={summary.totalTradedMwh} precision="MWH" />
      </div>
      <div className="flex items-center gap-1">
        <span className="text-text-secondary">Nominated:</span>
        <NumericCell value={summary.totalNominatedMwh} precision="MWH" />
      </div>
      <div className="flex items-center gap-1">
        <span className="text-text-secondary">Net Deviation:</span>
        <NumericCell value={summary.netDeviationMwh} precision="MWH" showSign />
      </div>
      <div className="flex items-center gap-1">
        <span className="text-text-secondary">Intervals w/ Deviation:</span>
        <span className="font-mono text-text-primary">
          {summary.intervalsWithDeviation} / {summary.totalIntervals}
        </span>
      </div>
    </div>
  );
}
