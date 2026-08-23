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
import { DaStatusBadge, type DaStatus } from './DaStatusBadge';
import { formatIntervalTime } from '@/lib/dateUtils';
import { cn } from '@/lib/cn';
import type { DaSettlementRowDto } from '@/schemas/daApi';

export interface DaSettlementGridProps {
  data: DaSettlementRowDto[] | undefined;
  isLoading: boolean;
  timezone: string;
}

interface SettlementDisplayRow extends DaSettlementRowDto {
  localTime: string;
  utcTime: string;
}

const columnHelper = createColumnHelper<SettlementDisplayRow>();

const columns = [
  columnHelper.accessor('localTime', {
    header: 'Interval',
    cell: (info) => (
      <span className="text-text-primary font-medium">{info.getValue()}</span>
    ),
    size: 140,
  }),
  columnHelper.accessor('tradeId', {
    header: 'Trade',
    cell: (info) => (
      <span className="text-text-secondary">{info.getValue()}</span>
    ),
    size: 100,
  }),
  columnHelper.accessor('direction', {
    header: 'Dir',
    cell: (info) => {
      const dir = info.getValue();
      return (
        <span
          className={cn(
            'text-xs font-medium',
            dir === 'BUY' ? 'text-status-settled' : 'text-numeric-negative',
          )}
        >
          {dir}
        </span>
      );
    },
    size: 60,
  }),
  columnHelper.accessor('price', {
    header: 'Price',
    cell: (info) => <NumericCell value={info.getValue()} precision="PRICE" />,
    size: 100,
  }),
  columnHelper.accessor('volumeMw', {
    header: 'Volume (MW)',
    cell: (info) => <NumericCell value={info.getValue()} precision="MW" />,
    size: 110,
  }),
  columnHelper.accessor('energyMwh', {
    header: 'Energy (MWh)',
    cell: (info) => <NumericCell value={info.getValue()} precision="MWH" />,
    size: 120,
  }),
  columnHelper.accessor('amount', {
    header: 'Amount',
    cell: (info) => (
      <NumericCell
        value={info.getValue()}
        precision="MONETARY"
        currency={info.row.original.currency}
      />
    ),
    size: 120,
  }),
  columnHelper.accessor('cellStatus', {
    header: 'Status',
    cell: (info) => <DaStatusBadge status={info.getValue() as DaStatus} />,
    size: 100,
  }),
];

/**
 * Settlement grid showing interval-level settlement data.
 * Columns: Interval, Trade, Dir, Price, Volume, Energy, Amount, Status.
 */
export function DaSettlementGrid({
  data,
  isLoading,
  timezone,
}: DaSettlementGridProps) {
  const rows = useMemo<SettlementDisplayRow[]>(() => {
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
    getRowId: (row) => `${row.intervalStart}-${row.tradeLegId}`,
  });

  if (isLoading) {
    return <SkeletonTable rows={24} columns={[14, 10, 6, 10, 11, 12, 12, 10]} />;
  }

  if (rows.length === 0) {
    return <EmptyState message="No settlement data for this delivery day." />;
  }

  return (
    <div className="overflow-auto border border-border-grid rounded max-h-[600px]">
      <table
        className="w-full border-collapse"
        role="grid"
        aria-label="Settlement interval data"
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
