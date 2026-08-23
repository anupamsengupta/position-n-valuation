import { useMemo } from 'react';
import {
  type ColumnDef,
  flexRender,
  getCoreRowModel,
  useReactTable,
} from '@tanstack/react-table';
import { NumericCell } from '@/components/primitives/NumericCell';
import { SkeletonTable } from '@/components/primitives/SkeletonRow';
import { EmptyState } from '@/components/primitives/EmptyState';
import { getDstInfo } from '@/lib/dateUtils';
import { parseNumericValue } from '@/lib/numberUtils';
import { pivotSettledIntervals, type PivotRow } from '@/lib/pivotIntervals';
import { cn } from '@/lib/cn';
import type { SettledDayGridProps } from './SettledDayGrid';

/**
 * Horizontal settled day grid: measures as rows, time intervals as columns.
 * Same data as SettledDayGrid, pivoted client-side.
 */
export function HorizontalSettledGrid({
  data,
  isLoading,
  timezone,
  expectedIntervalCount,
  hideZeroRows = false,
}: SettledDayGridProps) {
  const pivoted = useMemo(
    () => pivotSettledIntervals(data ?? [], timezone),
    [data, timezone],
  );

  const filteredPivotRows = useMemo(() => {
    if (!hideZeroRows) return pivoted.rows;
    return pivoted.rows.filter((row) => {
      // Price and Market Price are reference data — ignore for zero-row detection
      if (row.measure === 'Price' || row.measure === 'Market Price') return true;
      return pivoted.timeLabels.some((label) => {
        const v = parseNumericValue(row[label] as string | number | null | undefined);
        return v !== null && v !== 0;
      });
    });
  }, [pivoted, hideZeroRows]);

  const columns = useMemo<ColumnDef<PivotRow, unknown>[]>(() => {
    const cols: ColumnDef<PivotRow, unknown>[] = [
      {
        id: 'measure',
        header: 'Measure',
        accessorFn: (row) => row.measure,
        cell: (info) => (
          <span className="text-text-primary font-medium whitespace-nowrap">
            {info.getValue() as string}
          </span>
        ),
        size: 110,
      },
    ];

    for (const label of pivoted.timeLabels) {
      cols.push({
        id: `t_${label}`,
        header: label,
        accessorFn: (row) => row[label],
        cell: (info) => {
          const row = info.row.original;
          return (
            <NumericCell
              value={info.getValue() as string | number | null | undefined}
              precision={row.precision}
              currency={row.currency as string | undefined}
              showSign={!!row.showSign}
            />
          );
        },
        size: 72,
      });
    }

    return cols;
  }, [pivoted.timeLabels]);

  const table = useReactTable({
    data: filteredPivotRows,
    columns,
    getCoreRowModel: getCoreRowModel(),
    getRowId: (row) => row.measure,
  });

  const dstInfo = useMemo(
    () => (expectedIntervalCount ? getDstInfo(expectedIntervalCount) : null),
    [expectedIntervalCount],
  );

  if (isLoading) {
    return <SkeletonTable rows={7} columns={[11, 7, 7, 7, 7, 7, 7, 7]} />;
  }

  if (filteredPivotRows.length === 0) {
    return <EmptyState message="No settlement data for this day." />;
  }

  return (
    <div>
      {dstInfo?.isDstDay && (
        <div className="mb-2 px-3 py-1.5 text-xs text-text-secondary bg-bg-tertiary rounded" role="status">
          {dstInfo.label}
        </div>
      )}

      <div className="overflow-x-auto border border-border-grid rounded max-h-[400px]">
        <table className="border-collapse" aria-label="Settlement interval data (horizontal)">
          <thead className="sticky top-0 z-10 bg-bg-secondary">
            {table.getHeaderGroups().map((headerGroup) => (
              <tr key={headerGroup.id} className="h-7">
                {headerGroup.headers.map((header) => (
                  <th
                    key={header.id}
                    className={cn(
                      'px-2 py-1 text-xs font-semibold text-text-secondary text-left whitespace-nowrap border-b border-border-grid',
                      header.column.id === 'measure' &&
                        'sticky left-0 z-20 bg-bg-secondary',
                    )}
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
                className={cn('h-6', rowIndex % 2 === 1 && 'bg-bg-grid-even')}
              >
                {row.getVisibleCells().map((cell) => (
                  <td
                    key={cell.id}
                    className={cn(
                      'px-2 py-0.5 text-xs whitespace-nowrap',
                      cell.column.id === 'measure' &&
                        'sticky left-0 z-10 bg-bg-primary',
                    )}
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
