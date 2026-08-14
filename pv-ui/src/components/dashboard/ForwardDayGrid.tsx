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
import { formatIntervalTime, getDstInfo } from '@/lib/dateUtils';
import { cn } from '@/lib/cn';
import type { ForwardIntervalDetailDto } from '@/schemas/api';

export interface ForwardDayGridProps {
  data: ForwardIntervalDetailDto[] | undefined;
  isLoading: boolean;
  timezone: string;
  expectedIntervalCount?: number;
}

interface ForwardDayRow extends ForwardIntervalDetailDto {
  localTime: string;
  utcTime: string;
}

const columnHelper = createColumnHelper<ForwardDayRow>();

const columns = [
  columnHelper.accessor('localTime', {
    header: 'Time (Local)',
    cell: (info) => <span className="text-text-primary font-medium">{info.getValue()}</span>,
    size: 120,
  }),
  columnHelper.accessor('utcTime', {
    header: 'Time (UTC)',
    cell: (info) => <span className="text-text-muted">{info.getValue()}</span>,
    size: 100,
  }),
  columnHelper.accessor('resolvedQty', {
    header: 'MW',
    cell: (info) => <NumericCell value={info.getValue()} precision="MW" />,
    size: 90,
  }),
  columnHelper.accessor('resolvedEnergy', {
    header: 'MWh',
    cell: (info) => <NumericCell value={info.getValue()} precision="MWH" />,
    size: 90,
  }),
  columnHelper.accessor('evaluatedPrice', {
    header: 'Curve Price',
    cell: (info) => <NumericCell value={info.getValue()} precision="PRICE" />,
    size: 110,
  }),
  columnHelper.accessor('markValue', {
    header: 'Mark Value (indicative)',
    cell: (info) => (
      <NumericCell
        value={info.getValue()}
        precision="MONETARY"
        currency={info.row.original.currency ?? undefined}
      />
    ),
    size: 160,
  }),
  columnHelper.accessor('curveId', {
    header: 'Curve',
    cell: (info) => (
      <span className="text-text-muted text-xs truncate max-w-[100px] inline-block">
        {info.getValue() ?? '\u2014'}
      </span>
    ),
    size: 100,
  }),
  columnHelper.accessor('seriesKey', {
    header: 'Series',
    cell: (info) => (
      <span className="text-text-muted text-xs truncate max-w-[100px] inline-block">
        {info.getValue()}
      </span>
    ),
    size: 100,
  }),
];

/**
 * L4: Forward day interval grid showing forward mark data.
 * EMIR labeling: all values are indicative current marks (ADR-002).
 */
export function ForwardDayGrid({
  data,
  isLoading,
  timezone,
  expectedIntervalCount,
}: ForwardDayGridProps) {
  const rows = useMemo<ForwardDayRow[]>(() => {
    if (!data) return [];
    return data.map((cell) => {
      const times = formatIntervalTime(cell.intervalStart, timezone);
      return { ...cell, localTime: times.local, utcTime: times.utc };
    });
  }, [data, timezone]);

  const dstInfo = useMemo(
    () => (expectedIntervalCount ? getDstInfo(expectedIntervalCount) : null),
    [expectedIntervalCount],
  );

  const table = useReactTable({
    data: rows,
    columns,
    getCoreRowModel: getCoreRowModel(),
    getRowId: (row) => `${row.intervalStart}-${row.positionId ?? 'agg'}`,
  });

  if (isLoading) {
    return <SkeletonTable rows={24} columns={[12, 10, 9, 9, 11, 16, 10, 10]} />;
  }

  if (rows.length === 0) {
    return <EmptyState message="No forward mark data for this day. This may mean no volume is forecasted or no forward curve is available." />;
  }

  return (
    <div>
      <div className="mb-2 px-3 py-1.5 text-xs text-status-transition bg-status-transition/10 rounded font-medium">
        Forward Mark Data (Unrealized -- Indicative Only)
      </div>

      {dstInfo?.isDstDay && (
        <div className="mb-2 px-3 py-1.5 text-xs text-text-secondary bg-bg-tertiary rounded" role="status">
          {dstInfo.label}
        </div>
      )}

      <div className="overflow-auto border border-border-grid rounded max-h-[600px]">
        <table className="w-full border-collapse" aria-label="Forward interval data">
          <thead className="sticky top-0 z-10 bg-bg-secondary">
            {table.getHeaderGroups().map((headerGroup) => (
              <tr key={headerGroup.id} className="h-7">
                {headerGroup.headers.map((header) => (
                  <th
                    key={header.id}
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
                className={cn(
                  'h-6',
                  rowIndex % 2 === 1 && 'bg-bg-grid-even',
                )}
              >
                {row.getVisibleCells().map((cell) => (
                  <td
                    key={cell.id}
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
    </div>
  );
}
