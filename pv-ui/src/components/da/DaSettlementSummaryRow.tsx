import { NumericCell } from '@/components/primitives/NumericCell';
import type { DaSettlementGridDto } from '@/schemas/daApi';

export interface DaSettlementSummaryRowProps {
  summary: DaSettlementGridDto['summary'];
}

/**
 * Footer summary row for the DA settlement grid.
 * Shows total energy, total settlement, and VWAP.
 */
export function DaSettlementSummaryRow({ summary }: DaSettlementSummaryRowProps) {
  return (
    <div
      role="region"
      aria-label="Settlement summary"
      className="flex flex-wrap items-center gap-6 px-3 py-2 rounded border border-border-default bg-bg-secondary mt-2"
    >
      <div className="flex items-center gap-2 text-xs">
        <span className="text-text-muted font-medium">Total Energy:</span>
        <NumericCell value={summary.totalEnergyMwh} precision="MWH" />
      </div>
      <div className="flex items-center gap-2 text-xs">
        <span className="text-text-muted font-medium">Total Settlement:</span>
        <NumericCell
          value={summary.totalSettlement}
          precision="MONETARY"
          currency={summary.currency}
        />
      </div>
      <div className="flex items-center gap-2 text-xs">
        <span className="text-text-muted font-medium">VWAP:</span>
        <NumericCell value={summary.vwap} precision="PRICE" />
        <span className="text-text-muted">{summary.currency}/MWh</span>
      </div>
    </div>
  );
}
