import { useMemo } from 'react';
import {
  createColumnHelper,
  flexRender,
  getCoreRowModel,
  useReactTable,
} from '@tanstack/react-table';
import { NumericCell } from '@/components/primitives/NumericCell';
import { DeviationCell } from '@/components/primitives/DeviationCell';
import { DaStatusBadge } from './DaStatusBadge';
import { SkeletonTable } from '@/components/primitives/SkeletonRow';
import { EmptyState } from '@/components/primitives/EmptyState';
import { formatIntervalTime, getDstInfo } from '@/lib/dateUtils';
import { cn } from '@/lib/cn';
import type { NominationComparisonRowDto } from '@/schemas/daApi';

export interface DaNominationGridProps {
  data: NominationComparisonRowDto[] | undefined;
  isLoading: boolean;
  timezone: string;
  intervalCount?: number;
}

interface NominationDisplayRow extends NominationComparisonRowDto {
  localTime: string;
  utcTime: string;
}

const columnHelper = createColumnHelper<NominationDisplayRow>();

const columns = [
  columnHelper.accessor('localTime', {
    header: 'Interval (CET)',
    cell: (info) => (
      <span className="text-text-primary font-medium">{info.getValue()}</span>
    ),
    size: 140,
  }),
  columnHelper.accessor('tradedMw', {
    header: 'Traded (MW)',
    cell: (info) => <NumericCell value={info.getValue()} precision="MW" />,
    size: 110,
  }),
  columnHelper.accessor('nominatedMw', {
    header: 'Nominated (MW)',
    cell: (info) => <NumericCell value={info.getValue()} precision="MW" />,
    size: 120,
  }),
  columnHelper.accessor('deviationMw', {
    header: 'Deviation (MW)',
    cell: (info) => <DeviationCell value={info.getValue()} precision="MW" />,
    size: 120,
  }),
  columnHelper.accessor('status', {
    header: 'Status',
    cell: (info) => <DaStatusBadge status={info.getValue()} />,
    size: 100,
  }),
];

/**
 * Nomination comparison grid showing traded vs nominated volumes per interval.
 * Columns: Interval (CET), Traded MW, Nominated MW, Deviation MW, Status.
 * Uses role="grid" with ARIA attributes for accessibility.
 */
export function DaNominationGrid({
  data,
  isLoading,
  timezone,
  intervalCount,
}: DaNominationGridProps) {
  const rows = useMemo<NominationDisplayRow[]>(() => {
    if (!data) return [];
    return data.map((row) => {
      const times = formatIntervalTime(row.intervalStart, timezone);
      return { ...row, localTime: times.local, utcTime: times.utc };
    });
  }, [data, timezone]);

  const dstInfo = useMemo(
    () => (intervalCount ? getDstInfo(intervalCount) : null),
    [intervalCount],
  );

  const table = useReactTable({
    data: rows,
    columns,
    getCoreRowModel: getCoreRowModel(),
    getRowId: (row) => row.intervalStart,
  });

  if (isLoading) {
    return <SkeletonTable rows={24} columns={[14, 11, 12, 12, 10]} />;
  }

  if (rows.length === 0) {
    return <EmptyState message="No nomination data for this delivery day." />;
  }

  return (
    <div>
      {dstInfo?.isDstDay && (
        <div
          className="mb-2 px-3 py-1.5 text-xs text-text-secondary bg-bg-tertiary rounded"
          role="status"
        >
          {dstInfo.label}
        </div>
      )}

      <div className="overflow-auto border border-border-grid rounded max-h-[600px]">
        <table
          className="w-full border-collapse"
          role="grid"
          aria-label="Nomination comparison data"
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
    </div>
  );
}
