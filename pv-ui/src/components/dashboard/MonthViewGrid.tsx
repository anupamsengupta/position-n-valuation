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
import { formatLocalDate, getDstInfo } from '@/lib/dateUtils';
import { cn } from '@/lib/cn';
import type { DailyAggregateDto } from '@/schemas/api';

export interface MonthViewGridProps {
  data: DailyAggregateDto[] | undefined;
  isLoading: boolean;
  timezone: string;
  selectedDay: string | null;
  selectedDayRange: ReadonlySet<string>;
  onDayClick: (row: DailyAggregateDto) => void;
  onDayShiftClick: (row: DailyAggregateDto) => void;
}

interface MonthViewRow extends DailyAggregateDto {
  dateLabel: string;
  dstLabel: string;
}

const columnHelper = createColumnHelper<MonthViewRow>();

/**
 * L4 month view: daily aggregate rows within a delivery month.
 * Supports single-day click and Shift+Click contiguous range selection (F2).
 */
export function MonthViewGrid({
  data,
  isLoading,
  timezone,
  selectedDay,
  selectedDayRange,
  onDayClick,
  onDayShiftClick,
}: MonthViewGridProps) {
  const rowRefs = useRef<(HTMLTableRowElement | null)[]>([]);

  const rows = useMemo<MonthViewRow[]>(() => {
    if (!data) return [];
    return data.map((row) => {
      const dateLabel = formatLocalDate(row.dayStart, timezone);
      const dstInfo = getDstInfo(row.intervalCount);
      return { ...row, dateLabel, dstLabel: dstInfo.isDstDay ? dstInfo.label : '' };
    });
  }, [data, timezone]);

  const columns = useMemo(
    () => [
      columnHelper.accessor('dateLabel', {
        header: 'Date',
        cell: (info) => (
          <span className="font-medium text-text-primary">{info.getValue()}</span>
        ),
        size: 100,
      }),
      columnHelper.accessor('dayStatus', {
        header: 'Status',
        cell: (info) => {
          const val = info.getValue();
          if (val === 'SETTLED' || val === 'TODAY' || val === 'FORWARD') {
            return <StatusBadge status={val} />;
          }
          return <span className="text-text-muted text-xs">{val}</span>;
        },
        size: 90,
      }),
      columnHelper.accessor('intervalCount', {
        header: 'Intervals',
        cell: (info) => {
          const count = info.getValue();
          const dstInfo = getDstInfo(count);
          return (
            <span className="text-text-secondary" title={dstInfo.isDstDay ? dstInfo.label : `${count} intervals`}>
              {count}
            </span>
          );
        },
        size: 70,
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
      columnHelper.accessor('forwardMarkValue', {
        header: 'Fwd Mark (indicative)',
        cell: (info) => (
          <NumericCell
            value={info.getValue()}
            precision="MONETARY"
            currency={info.row.original.currency}
          />
        ),
        size: 150,
      }),
    ],
    [],
  );

  const table = useReactTable({
    data: rows,
    columns,
    getCoreRowModel: getCoreRowModel(),
    getRowId: (row) => row.dayStart,
  });

  const handleRowClick = useCallback(
    (e: React.MouseEvent, row: DailyAggregateDto) => {
      if (e.shiftKey) {
        onDayShiftClick(row);
      } else {
        onDayClick(row);
      }
    },
    [onDayClick, onDayShiftClick],
  );

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLTableRowElement>, row: DailyAggregateDto, rowIndex: number) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        if (e.shiftKey) {
          onDayShiftClick(row);
        } else {
          onDayClick(row);
        }
      } else if (e.key === 'ArrowDown') {
        e.preventDefault();
        if (e.shiftKey) {
          const nextRow = rows[rowIndex + 1];
          if (nextRow) onDayShiftClick(nextRow);
        }
        rowRefs.current[rowIndex + 1]?.focus();
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        if (e.shiftKey) {
          const prevRow = rows[rowIndex - 1];
          if (prevRow) onDayShiftClick(prevRow);
        }
        rowRefs.current[rowIndex - 1]?.focus();
      }
    },
    [onDayClick, onDayShiftClick, rows],
  );

  // Roving tabindex: selected day or first row
  const focusableRowIndex = useMemo(() => {
    if (selectedDay) {
      const idx = rows.findIndex((r) => r.dayStart === selectedDay);
      if (idx >= 0) return idx;
    }
    return 0;
  }, [rows, selectedDay]);

  // Live region for range announcements
  const liveText = useMemo(() => {
    if (selectedDayRange.size <= 1) return '';
    return `${selectedDayRange.size} days selected`;
  }, [selectedDayRange.size]);

  if (isLoading) {
    return <SkeletonTable rows={15} columns={[10, 9, 7, 10, 11, 12, 9, 10, 15]} />;
  }

  if (rows.length === 0) {
    return <EmptyState message="No daily data for the selected period." />;
  }

  return (
    <>
      {/* Live region for screen reader range announcements */}
      <div className="sr-only" role="status" aria-live="polite" aria-atomic="true">
        {liveText}
      </div>
      <div
        className="overflow-auto border border-border-grid rounded max-h-[500px]"
        role="grid"
        aria-label="Daily aggregate data"
        aria-multiselectable="true"
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
            {table.getRowModel().rows.map((row, rowIndex) => {
              const isInRange = selectedDayRange.has(row.original.dayStart);
              const isAnchor = row.original.dayStart === selectedDay;
              return (
                <tr
                  key={row.id}
                  ref={(el) => { rowRefs.current[rowIndex] = el; }}
                  role="row"
                  tabIndex={rowIndex === focusableRowIndex ? 0 : -1}
                  aria-selected={isInRange}
                  className={cn(
                    'h-6 cursor-pointer transition-colors',
                    'hover:bg-interactive-row-hover',
                    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-interactive-focus',
                    isInRange && 'bg-interactive-row-selected',
                    isAnchor && 'border-l-2 border-l-interactive-focus',
                    rowIndex % 2 === 1 && !isInRange && 'bg-bg-grid-even',
                  )}
                  onClick={(e) => handleRowClick(e, row.original)}
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
    </>
  );
}
