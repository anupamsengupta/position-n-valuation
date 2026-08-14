import { useMemo, useCallback, useRef, useEffect } from 'react';
import {
  createColumnHelper,
  flexRender,
  getCoreRowModel,
  useReactTable,
} from '@tanstack/react-table';
import { NumericCell } from '@/components/primitives/NumericCell';
import { StatusBadge } from '@/components/primitives/StatusBadge';
import { SkeletonTable } from '@/components/primitives/SkeletonRow';
import { EmptyState } from '@/components/primitives/EmptyState';
import { cn } from '@/lib/cn';
import type { PositionContributionDto } from '@/schemas/api';
import type { PeriodStatus } from '@/schemas/types';

export interface PositionLedgerProps {
  data: PositionContributionDto[] | undefined;
  isLoading: boolean;
  periodLabel: string;
  periodStatus: PeriodStatus;
  selectedPositionId: string | null;
  onRowClick: (row: PositionContributionDto) => void;
  onClose: () => void;
}

const columnHelper = createColumnHelper<PositionContributionDto>();

const columns = [
  columnHelper.accessor('tradeId', {
    header: 'Trade ID',
    cell: (info) => <span className="font-medium text-text-primary">{info.getValue()}</span>,
    size: 100,
  }),
  columnHelper.accessor('tradeLegId', {
    header: 'Leg',
    cell: (info) => <span className="text-text-secondary">{info.getValue()}</span>,
    size: 60,
  }),
  columnHelper.accessor('deliveryStatus', {
    header: 'Status',
    cell: (info) => {
      const val = info.getValue();
      if (val === 'SETTLED' || val === 'PARTIAL' || val === 'FORWARD') {
        return <StatusBadge status={val} />;
      }
      return <span className="text-text-muted text-xs">{val}</span>;
    },
    size: 90,
  }),
  columnHelper.accessor('settledMw', {
    header: 'Settled MW',
    cell: (info) => <NumericCell value={info.getValue()} precision="MW" />,
    size: 100,
  }),
  columnHelper.accessor('settledMwh', {
    header: 'Settled MWh',
    cell: (info) => <NumericCell value={info.getValue()} precision="MWH" />,
    size: 110,
  }),
  columnHelper.accessor('avgPrice', {
    header: 'Avg Price',
    cell: (info) => <NumericCell value={info.getValue()} precision="PRICE" />,
    size: 100,
  }),
  columnHelper.accessor('realizedPnl', {
    header: 'Realized PnL',
    cell: (info) => (
      <NumericCell
        value={info.getValue()}
        precision="MONETARY"
        currency={info.row.original.currency}
        showSign
      />
    ),
    size: 120,
  }),
  columnHelper.accessor('forwardMw', {
    header: 'Fwd MW',
    cell: (info) => <NumericCell value={info.getValue()} precision="MW" />,
    size: 90,
  }),
  columnHelper.accessor('forwardMwh', {
    header: 'Fwd MWh',
    cell: (info) => <NumericCell value={info.getValue()} precision="MWH" />,
    size: 100,
  }),
  columnHelper.accessor('unrealizedMtm', {
    header: 'Unrealized MtM (indicative)',
    cell: (info) => (
      <NumericCell
        value={info.getValue()}
        precision="MONETARY"
        currency={info.row.original.currency}
        showSign
      />
    ),
    size: 170,
  }),
];

/**
 * L3: Position ledger table showing per-position contributions
 * for the selected period.
 * Focus is moved to this section on mount for keyboard/screen reader discoverability.
 */
export function PositionLedger({
  data,
  isLoading,
  periodLabel,
  periodStatus,
  selectedPositionId,
  onRowClick,
  onClose,
}: PositionLedgerProps) {
  const sectionRef = useRef<HTMLElement>(null);
  const rowRefs = useRef<(HTMLTableRowElement | null)[]>([]);

  // S-2 fix: move focus to section on mount so users discover the new panel
  useEffect(() => {
    sectionRef.current?.focus();
  }, []);

  const table = useReactTable({
    data: data ?? [],
    columns,
    getCoreRowModel: getCoreRowModel(),
    getRowId: (row) => row.positionId ?? `${row.tradeId}-${row.tradeLegId}`,
  });

  const handleRowClick = useCallback(
    (row: PositionContributionDto) => {
      onRowClick(row);
    },
    [onRowClick],
  );

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLTableRowElement>, row: PositionContributionDto, rowIndex: number) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        handleRowClick(row);
      } else if (e.key === 'ArrowDown') {
        e.preventDefault();
        rowRefs.current[rowIndex + 1]?.focus();
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        rowRefs.current[rowIndex - 1]?.focus();
      } else if (e.key === 'Escape') {
        e.preventDefault();
        onClose();
      }
    },
    [handleRowClick, onClose],
  );

  const statusBadgeStatus = useMemo(() => {
    if (periodStatus === 'TRANSITION') return 'TRANSITION' as const;
    if (periodStatus === 'SETTLED') return 'SETTLED' as const;
    return 'FORWARD' as const;
  }, [periodStatus]);

  // Determine roving tabindex target
  const focusableRowIndex = useMemo(() => {
    if (selectedPositionId && data) {
      const idx = data.findIndex((r) => r.positionId === selectedPositionId);
      if (idx >= 0) return idx;
    }
    return 0;
  }, [data, selectedPositionId]);

  return (
    <section aria-label="Position Ledger" ref={sectionRef} tabIndex={-1}>
      <div className="flex items-center justify-between mb-2">
        <div className="flex items-center gap-2">
          <h3 className="text-sm font-semibold text-text-primary">
            Position Contributions
          </h3>
          <span className="text-xs text-text-secondary">{periodLabel}</span>
          <StatusBadge status={statusBadgeStatus} />
        </div>
        <button
          type="button"
          onClick={onClose}
          className="px-2 py-1 text-xs text-text-secondary hover:text-text-primary
                     border border-border-default rounded hover:bg-bg-secondary
                     focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus"
          aria-label="Close position ledger"
        >
          Close
        </button>
      </div>

      {isLoading ? (
        <SkeletonTable rows={8} columns={[10, 6, 8, 10, 11, 10, 12, 9, 10, 14]} />
      ) : !data || data.length === 0 ? (
        <EmptyState message="No position contributions for this period." />
      ) : (
        <div
          className="overflow-auto border border-border-grid rounded"
          role="grid"
          aria-label="Position contributions"
          aria-rowcount={data.length}
        >
          <table className="w-full border-collapse">
            <thead className="sticky top-0 z-10 bg-bg-secondary">
              {table.getHeaderGroups().map((headerGroup) => (
                <tr key={headerGroup.id} role="row" className="h-7">
                  {headerGroup.headers.map((header) => (
                    <th
                      key={header.id}
                      role="columnheader"
                      className="px-2 py-1 text-xs font-semibold text-text-secondary text-left whitespace-nowrap border-b border-border-grid"
                      style={{ width: header.getSize() }}
                    >
                      {flexRender(header.column.columnDef.header, header.getContext())}
                    </th>
                  ))}
                </tr>
              ))}
            </thead>
            <tbody>
              {table.getRowModel().rows.map((row, rowIndex) => {
                const posId = row.original.positionId;
                return (
                  <tr
                    key={row.id}
                    ref={(el) => { rowRefs.current[rowIndex] = el; }}
                    role="row"
                    tabIndex={rowIndex === focusableRowIndex ? 0 : -1}
                    aria-selected={posId === selectedPositionId}
                    className={cn(
                      'h-6 cursor-pointer transition-colors',
                      'hover:bg-interactive-row-hover',
                      'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-interactive-focus',
                      posId === selectedPositionId && 'bg-interactive-row-selected',
                      rowIndex % 2 === 1 && 'bg-bg-grid-even',
                    )}
                    onClick={() => handleRowClick(row.original)}
                    onKeyDown={(e) => handleKeyDown(e, row.original, rowIndex)}
                  >
                    {row.getVisibleCells().map((cell) => (
                      <td
                        key={cell.id}
                        role="gridcell"
                        className="px-2 py-0.5 text-xs whitespace-nowrap"
                      >
                        {flexRender(cell.column.columnDef.cell, cell.getContext())}
                      </td>
                    ))}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
