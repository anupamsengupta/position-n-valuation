import { Fragment, useMemo } from 'react';
import {
  createColumnHelper,
  flexRender,
  getCoreRowModel,
  useReactTable,
} from '@tanstack/react-table';
import { NumericCell } from '@/components/primitives/NumericCell';
import { SkeletonTable } from '@/components/primitives/SkeletonRow';
import { EmptyState } from '@/components/primitives/EmptyState';
import { formatIntervalTime, formatLocalDate, getDstInfo } from '@/lib/dateUtils';
import { cn } from '@/lib/cn';
import { parseNumericValue } from '@/lib/numberUtils';
import { DayBoundaryRow } from './DayBoundaryRow';
import type { SettlementCellDto } from '@/schemas/api';

export interface SettledDayGridProps {
  data: SettlementCellDto[] | undefined;
  isLoading: boolean;
  timezone: string;
  expectedIntervalCount?: number;
  isMultiDay?: boolean;
  hideZeroRows?: boolean;
}

interface SettledDayRow extends SettlementCellDto {
  localTime: string;
  utcTime: string;
}

const columnHelper = createColumnHelper<SettledDayRow>();

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
  columnHelper.accessor('volumeMw', {
    header: 'MW',
    cell: (info) => <NumericCell value={info.getValue()} precision="MW" />,
    size: 90,
  }),
  columnHelper.accessor('volumeMwh', {
    header: 'MWh',
    cell: (info) => <NumericCell value={info.getValue()} precision="MWH" />,
    size: 90,
  }),
  columnHelper.accessor('price', {
    header: 'Price',
    cell: (info) => <NumericCell value={info.getValue()} precision="PRICE" />,
    size: 100,
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
  columnHelper.accessor('marketPrice', {
    header: 'Market Price',
    cell: (info) => <NumericCell value={info.getValue()} precision="PRICE" />,
    size: 110,
  }),
  columnHelper.accessor('marketAmount', {
    header: 'Market Amount',
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
    size: 110,
  }),
];

/**
 * L4: Settled day interval grid showing settlement cells.
 * Displays 15-min / 30-min / hourly intervals with dual local+UTC time.
 */
const SETTLED_MEASURE_FIELDS: (keyof SettlementCellDto)[] = [
  'volumeMw', 'volumeMwh', 'amount', 'marketAmount', 'pnl',
];

export function SettledDayGrid({
  data,
  isLoading,
  timezone,
  expectedIntervalCount,
  isMultiDay = false,
  hideZeroRows = false,
}: SettledDayGridProps) {
  const allRows = useMemo<SettledDayRow[]>(() => {
    if (!data) return [];
    return data.map((cell) => {
      const times = formatIntervalTime(cell.intervalStart, timezone);
      return { ...cell, localTime: times.local, utcTime: times.utc };
    });
  }, [data, timezone]);

  const rows = useMemo(() => {
    if (!hideZeroRows) return allRows;
    return allRows.filter((row) =>
      SETTLED_MEASURE_FIELDS.some((f) => {
        const v = parseNumericValue(row[f] as string | number | null | undefined);
        return v !== null && v !== 0;
      }),
    );
  }, [allRows, hideZeroRows]);

  // Compute day boundaries for multi-day view
  const dayBoundaries = useMemo(() => {
    if (!isMultiDay || rows.length === 0) return new Set<number>();
    const boundaries = new Set<number>();
    let prevDate = '';
    for (let i = 0; i < rows.length; i++) {
      const localDate = formatLocalDate(rows[i]!.intervalStart, timezone);
      if (localDate !== prevDate && prevDate !== '') {
        boundaries.add(i);
      }
      prevDate = localDate;
    }
    return boundaries;
  }, [isMultiDay, rows, timezone]);

  const dstInfo = useMemo(
    () => (expectedIntervalCount ? getDstInfo(expectedIntervalCount) : null),
    [expectedIntervalCount],
  );

  const table = useReactTable({
    data: rows,
    columns,
    getCoreRowModel: getCoreRowModel(),
    getRowId: (row) => row.cellId,
  });

  if (isLoading) {
    return <SkeletonTable rows={24} columns={[12, 10, 9, 9, 10, 12, 11, 13, 11]} />;
  }

  if (rows.length === 0) {
    return <EmptyState message="No settlement data for this day." />;
  }

  return (
    <div>
      {dstInfo?.isDstDay && (
        <div className="mb-2 px-3 py-1.5 text-xs text-text-secondary bg-bg-tertiary rounded" role="status">
          {dstInfo.label}
        </div>
      )}

      <div className="overflow-auto border border-border-grid rounded max-h-[600px]">
        <table className="w-full border-collapse" aria-label="Settlement interval data">
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
            {table.getRowModel().rows.map((row, rowIndex) => {
              const boundary = dayBoundaries.has(rowIndex);
              return (
                <Fragment key={row.id}>
                  {boundary && (
                    <DayBoundaryRow
                      key={`boundary-${rowIndex}`}
                      dateLabel={new Date(row.original.intervalStart).toLocaleDateString('en-GB', {
                        day: '2-digit', month: 'short', year: 'numeric', timeZone: timezone,
                      })}
                      columnCount={columns.length}
                    />
                  )}
                  <tr
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
                </Fragment>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
