import { useMemo, useCallback } from 'react';
import {
  createColumnHelper,
  flexRender,
  getCoreRowModel,
  getSortedRowModel,
  useReactTable,
  type SortingState,
} from '@tanstack/react-table';
import { useState } from 'react';
import { NumericCell } from '@/components/primitives/NumericCell';
import { DaStatusBadge, type DaStatus } from './DaStatusBadge';
import { SkeletonTable } from '@/components/primitives/SkeletonRow';
import { EmptyState } from '@/components/primitives/EmptyState';
import { cn } from '@/lib/cn';
import type { AuctionImportSessionDto } from '@/schemas/daApi';

export interface DaImportHistoryTableProps {
  data: AuctionImportSessionDto[] | undefined;
  isLoading: boolean;
  selectedSessionId: string | null;
  onSessionSelect: (sessionId: string) => void;
}

const columnHelper = createColumnHelper<AuctionImportSessionDto>();

function formatTimestamp(isoString: string): string {
  const date = new Date(isoString);
  return new Intl.DateTimeFormat('en-GB', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
    timeZone: 'Europe/Berlin',
    timeZoneName: 'short',
  }).format(date);
}

const columns = [
  columnHelper.accessor('deliveryDay', {
    header: 'Delivery Day',
    cell: (info) => (
      <span className="text-text-primary font-medium">{info.getValue()}</span>
    ),
    size: 120,
  }),
  columnHelper.accessor('biddingZone', {
    header: 'Zone',
    cell: (info) => (
      <span className="text-text-secondary">{info.getValue()}</span>
    ),
    size: 80,
  }),
  columnHelper.accessor('status', {
    header: 'Status',
    cell: (info) => <DaStatusBadge status={info.getValue() as DaStatus} />,
    size: 100,
    enableSorting: false,
  }),
  columnHelper.accessor(
    (row) => row.tradeIds?.length ?? 0,
    {
      id: 'tradesCount',
      header: 'Trades',
      cell: (info) => (
        <span className="text-text-primary font-mono tabular-nums text-right">
          {info.getValue()}
        </span>
      ),
      size: 70,
    },
  ),
  columnHelper.accessor('importedTotalMwh', {
    header: 'Total MWh',
    cell: (info) => <NumericCell value={info.getValue()} precision="MWH" />,
    size: 120,
  }),
  columnHelper.accessor('importTimestamp', {
    header: 'Imported At',
    cell: (info) => (
      <span className="text-text-secondary text-xs">
        {formatTimestamp(info.getValue())}
      </span>
    ),
    size: 180,
    sortDescFirst: true,
  }),
];

/**
 * Import history table showing past auction import sessions.
 * Sorted by importTimestamp DESC by default. Row click selects a session.
 */
export function DaImportHistoryTable({
  data,
  isLoading,
  selectedSessionId,
  onSessionSelect,
}: DaImportHistoryTableProps) {
  const [sorting, setSorting] = useState<SortingState>([
    { id: 'importTimestamp', desc: true },
  ]);

  const rows = useMemo(() => data ?? [], [data]);

  const table = useReactTable({
    data: rows,
    columns,
    state: { sorting },
    onSortingChange: setSorting,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
    getRowId: (row) => row.sessionId,
  });

  const handleRowClick = useCallback(
    (sessionId: string) => {
      onSessionSelect(sessionId);
    },
    [onSessionSelect],
  );

  const handleRowKeyDown = useCallback(
    (e: React.KeyboardEvent, sessionId: string) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        onSessionSelect(sessionId);
      }
    },
    [onSessionSelect],
  );

  if (isLoading) {
    return <SkeletonTable rows={8} columns={[18, 12, 15, 10, 18, 27]} />;
  }

  if (rows.length === 0) {
    return <EmptyState message="No import sessions found." />;
  }

  return (
    <div className="overflow-auto border border-border-grid rounded max-h-[400px]">
      <table className="w-full border-collapse" aria-label="Import history">
        <thead className="sticky top-0 z-10 bg-bg-secondary">
          {table.getHeaderGroups().map((headerGroup) => (
            <tr key={headerGroup.id} className="h-7">
              {headerGroup.headers.map((header) => {
                const canSort = header.column.getCanSort();
                const sorted = header.column.getIsSorted();
                return (
                  <th
                    key={header.id}
                    className={cn(
                      'px-2 py-1 text-xs font-semibold text-text-secondary whitespace-nowrap border-b border-border-grid',
                      header.index === 0 ? 'text-left' : 'text-left',
                      canSort && 'cursor-pointer select-none hover:text-text-primary',
                    )}
                    style={{ width: header.getSize() }}
                    onClick={canSort ? header.column.getToggleSortingHandler() : undefined}
                    onKeyDown={
                      canSort
                        ? (e) => {
                            if (e.key === 'Enter' || e.key === ' ') {
                              e.preventDefault();
                              header.column.toggleSorting();
                            }
                          }
                        : undefined
                    }
                    tabIndex={canSort ? 0 : undefined}
                    aria-sort={
                      sorted === 'asc' ? 'ascending' : sorted === 'desc' ? 'descending' : 'none'
                    }
                    role={canSort ? 'columnheader' : undefined}
                  >
                    {flexRender(header.column.columnDef.header, header.getContext())}
                    {sorted === 'asc' && <span aria-hidden="true"> {'\u25B2'}</span>}
                    {sorted === 'desc' && <span aria-hidden="true"> {'\u25BC'}</span>}
                  </th>
                );
              })}
            </tr>
          ))}
        </thead>
        <tbody>
          {table.getRowModel().rows.map((row, rowIndex) => {
            const isSelected = row.original.sessionId === selectedSessionId;
            return (
              <tr
                key={row.id}
                role="row"
                tabIndex={0}
                aria-selected={isSelected}
                onClick={() => handleRowClick(row.original.sessionId)}
                onKeyDown={(e) => handleRowKeyDown(e, row.original.sessionId)}
                className={cn(
                  'h-6 cursor-pointer transition-colors',
                  isSelected
                    ? 'bg-interactive-focus/10'
                    : rowIndex % 2 === 1
                      ? 'bg-bg-grid-even hover:bg-bg-tertiary'
                      : 'hover:bg-bg-tertiary',
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
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
