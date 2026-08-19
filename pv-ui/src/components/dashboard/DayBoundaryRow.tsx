export interface DayBoundaryRowProps {
  dateLabel: string;
  dstLabel?: string;
  columnCount: number;
}

/**
 * Visual separator between days in multi-day sub-daily grids.
 * Not a data row — skipped by keyboard navigation.
 */
export function DayBoundaryRow({ dateLabel, dstLabel, columnCount }: DayBoundaryRowProps) {
  return (
    <tr role="presentation" className="h-5 border-t-2 border-border-grid bg-bg-secondary">
      <td colSpan={columnCount} className="px-2 py-0.5 text-xs font-semibold text-text-secondary">
        {dateLabel}
        {dstLabel && (
          <span className="ml-2 font-normal text-text-muted">({dstLabel})</span>
        )}
      </td>
    </tr>
  );
}
