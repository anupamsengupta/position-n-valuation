import { useMemo, useCallback, useRef } from 'react';
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
import { derivePeriodStatus } from '@/lib/statusUtils';
import { formatPeriodLabel } from '@/lib/dateUtils';
import { parseNumericValue } from '@/lib/numberUtils';
import { cn } from '@/lib/cn';
import type { RollupCellDto } from '@/schemas/api';
import type { PeriodStatus, TimeGranularity } from '@/schemas/types';

export interface RollupGridRow extends RollupCellDto {
  periodStatus: PeriodStatus;
  totalValue: number | null;
}

export interface RollupGridProps {
  data: RollupCellDto[] | undefined;
  isLoading: boolean;
  granularity: TimeGranularity;
  timezone: string;
  selectedPeriodStart: string | null;
  onRowClick: (row: RollupGridRow) => void;
}

const columnHelper = createColumnHelper<RollupGridRow>();

/**
 * L2: Rollup grid showing period-level aggregates.
 * Each row represents a period (day/week/month/year) with settled + forward values.
 * Keyboard: ArrowUp/ArrowDown navigate rows, Enter/Space selects.
 */
export function RollupGrid({
  data,
  isLoading,
  granularity,
  timezone,
  selectedPeriodStart,
  onRowClick,
}: RollupGridProps) {
  const rowRefs = useRef<(HTMLTableRowElement | null)[]>([]);

  // Derive period status and total value for each row
  const rows = useMemo<RollupGridRow[]>(() => {
    if (!data) return [];
    return data.map((cell) => {
      const periodStatus = derivePeriodStatus(cell.periodStart, cell.periodEnd);
      const settled = parseNumericValue(cell.settledValue);
      const forward = parseNumericValue(cell.forwardMarkValue);
      const totalValue =
        settled !== null && forward !== null ? settled + forward : settled ?? forward;
      return { ...cell, periodStatus, totalValue };
    });
  }, [data]);

  const columns = useMemo(
    () => [
      columnHelper.accessor('periodStart', {
        header: 'Period',
        cell: (info) => (
          <span className="font-medium text-text-primary text-xs">
            {formatPeriodLabel(info.getValue(), granularity, timezone)}
          </span>
        ),
        size: 120,
      }),
      columnHelper.accessor('periodStatus', {
        header: 'Status',
        cell: (info) => <StatusBadge status={info.getValue()} />,
        size: 100,
      }),
      columnHelper.accessor('netMw', {
        header: 'Net MW',
        cell: (info) => <NumericCell value={info.getValue()} precision="MW" />,
        size: 100,
      }),
      columnHelper.accessor('netMwh', {
        header: 'Net MWh',
        cell: (info) => <NumericCell value={info.getValue()} precision="MWH" />,
        size: 120,
      }),
      columnHelper.accessor('settledValue', {
        header: 'Settled Value',
        cell: (info) => (
          <NumericCell
            value={info.getValue()}
            precision="MONETARY"
            currency={info.row.original.currency}
          />
        ),
        size: 130,
      }),
      columnHelper.accessor('marketValue', {
        header: 'Market Value',
        cell: (info) => (
          <NumericCell
            value={info.getValue()}
            precision="MONETARY"
            currency={info.row.original.currency}
          />
        ),
        size: 130,
      }),
      columnHelper.accessor('pnl', {
        header: 'PnL',
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
      columnHelper.accessor('forwardMarkValue', {
        header: 'Forward Mark (indicative)',
        cell: (info) => (
          <NumericCell
            value={info.getValue()}
            precision="MONETARY"
            currency={info.row.original.currency}
          />
        ),
        size: 160,
      }),
      columnHelper.accessor('totalValue', {
        header: 'Total Value',
        cell: (info) => (
          <NumericCell
            value={info.getValue()}
            precision="MONETARY"
            currency={info.row.original.currency}
          />
        ),
        size: 130,
      }),
    ],
    [granularity, timezone],
  );

  const table = useReactTable({
    data: rows,
    columns,
    getCoreRowModel: getCoreRowModel(),
    getRowId: (row) => row.periodStart,
  });

  const handleRowClick = useCallback(
    (row: RollupGridRow) => {
      onRowClick(row);
    },
    [onRowClick],
  );

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLTableRowElement>, row: RollupGridRow, rowIndex: number) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        handleRowClick(row);
      } else if (e.key === 'ArrowDown') {
        e.preventDefault();
        rowRefs.current[rowIndex + 1]?.focus();
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        rowRefs.current[rowIndex - 1]?.focus();
      }
    },
    [handleRowClick],
  );

  // Determine which row gets tabIndex={0} (roving tabindex)
  const focusableRowIndex = useMemo(() => {
    if (selectedPeriodStart) {
      const idx = rows.findIndex((r) => r.periodStart === selectedPeriodStart);
      if (idx >= 0) return idx;
    }
    return 0;
  }, [rows, selectedPeriodStart]);

  if (isLoading) {
    return (
      <div aria-busy="true" aria-label="Loading rollup data">
        <SkeletonTable rows={12} columns={[12, 8, 10, 12, 12, 12, 10, 14, 12]} />
      </div>
    );
  }

  if (rows.length === 0) {
    return (
      <EmptyState message="No rollup data available for the selected date range and granularity." />
    );
  }

  return (
    <div
      className="overflow-auto border border-border-grid rounded"
      role="grid"
      aria-label="Period rollup data"
      aria-rowcount={rows.length}
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
          {table.getRowModel().rows.map((row, rowIndex) => (
            <tr
              key={row.id}
              ref={(el) => { rowRefs.current[rowIndex] = el; }}
              role="row"
              tabIndex={rowIndex === focusableRowIndex ? 0 : -1}
              aria-selected={row.original.periodStart === selectedPeriodStart}
              className={cn(
                'h-6 cursor-pointer transition-colors',
                'hover:bg-interactive-row-hover',
                'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-interactive-focus',
                row.original.periodStart === selectedPeriodStart && 'bg-interactive-row-selected',
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
          ))}
        </tbody>
      </table>
    </div>
  );
}
