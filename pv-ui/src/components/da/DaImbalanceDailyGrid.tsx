import { useMemo } from 'react';
import {
  createColumnHelper,
  flexRender,
  getCoreRowModel,
  useReactTable,
} from '@tanstack/react-table';
import { NumericCell } from '@/components/primitives/NumericCell';
import { SkeletonTable } from '@/components/primitives/SkeletonRow';
import { EmptyState } from '@/components/primitives/EmptyState';
import { formatIntervalTime } from '@/lib/dateUtils';
import { cn } from '@/lib/cn';
import { isNegativeValue } from '@/lib/numberUtils';
import type { ImbalanceRowDto } from '@/schemas/daApi';

export interface DaImbalanceDailyGridProps {
  data: ImbalanceRowDto[] | undefined;
  isLoading: boolean;
  timezone: string;
}

interface ImbalanceDailyDisplayRow extends ImbalanceRowDto {
  localTime: string;
  utcTime: string;
}

const columnHelper = createColumnHelper<ImbalanceDailyDisplayRow>();

const columns = [
  columnHelper.accessor('localTime', {
    header: 'Interval',
    cell: (info) => (
      <span className="text-text-primary font-medium">{info.getValue()}</span>
    ),
    size: 140,
  }),
  columnHelper.accessor('nominatedMw', {
    header: 'Nominated (MW)',
    cell: (info) => <NumericCell value={info.getValue()} precision="MW" />,
    size: 120,
  }),
  columnHelper.accessor('actualMw', {
    header: 'Actual (MW)',
    cell: (info) => <NumericCell value={info.getValue()} precision="MW" />,
    size: 110,
  }),
  columnHelper.accessor('imbalanceMw', {
    header: 'Imbalance (MW)',
    cell: (info) => <NumericCell value={info.getValue()} precision="MW" showSign />,
    size: 120,
  }),
  columnHelper.accessor('rebapPrice', {
    header: 'reBAP Price',
    cell: (info) => <NumericCell value={info.getValue()} precision="PRICE" />,
    size: 110,
  }),
  columnHelper.accessor('amount', {
    header: 'Amount',
    cell: (info) => {
      const val = info.getValue();
      const isNeg = isNegativeValue(val);
      return (
        <span className={cn(isNeg ? 'text-numeric-negative' : 'text-status-settled')}>
          <NumericCell
            value={val}
            precision="MONETARY"
            currency={info.row.original.currency}
            showSign
            colorNegative={false}
          />
        </span>
      );
    },
    size: 130,
  }),
];

/**
 * Daily imbalance grid showing interval-level imbalance data.
 * Columns: Interval, Nominated MW, Actual MW, Imbalance MW, reBAP Price, Amount.
 * Amount cells colored green for positive (credit), red for negative (debit).
 */
export function DaImbalanceDailyGrid({
  data,
  isLoading,
  timezone,
}: DaImbalanceDailyGridProps) {
  const rows = useMemo<ImbalanceDailyDisplayRow[]>(() => {
    if (!data) return [];
    return data.map((row) => {
      const times = formatIntervalTime(row.intervalStart, timezone);
      return { ...row, localTime: times.local, utcTime: times.utc };
    });
  }, [data, timezone]);

  const table = useReactTable({
    data: rows,
    columns,
    getCoreRowModel: getCoreRowModel(),
    getRowId: (row) => row.intervalStart,
  });

  if (isLoading) {
    return <SkeletonTable rows={24} columns={[14, 12, 11, 12, 11, 13]} />;
  }

  if (rows.length === 0) {
    return <EmptyState message="No imbalance data for this delivery day." />;
  }

  return (
    <div className="overflow-auto border border-border-grid rounded max-h-[600px]">
      <table
        className="w-full border-collapse"
        role="grid"
        aria-label="Daily imbalance data"
        aria-rowcount={rows.length + 1}
      >
        <thead className="sticky top-0 z-10 bg-bg-secondary">
          {table.getHeaderGroups().map((headerGroup) => (
            <tr key={headerGroup.id} className="h-7" role="row">
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
              role="row"
              aria-rowindex={rowIndex + 2}
              className={cn(
                'h-6',
                rowIndex % 2 === 1 && 'bg-bg-grid-even',
              )}
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
