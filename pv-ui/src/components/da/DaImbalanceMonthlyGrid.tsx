import {
  createColumnHelper,
  flexRender,
  getCoreRowModel,
  useReactTable,
} from '@tanstack/react-table';
import { NumericCell } from '@/components/primitives/NumericCell';
import { SkeletonTable } from '@/components/primitives/SkeletonRow';
import { EmptyState } from '@/components/primitives/EmptyState';
import { cn } from '@/lib/cn';
import type { ImbalanceDaySummaryDto } from '@/schemas/daApi';

export interface DaImbalanceMonthlyGridProps {
  data: ImbalanceDaySummaryDto[] | undefined;
  isLoading: boolean;
  onDayClick?: (deliveryDay: string) => void;
}

const columnHelper = createColumnHelper<ImbalanceDaySummaryDto>();

/**
 * Monthly imbalance grid showing one row per delivery day.
 * Clicking a day navigates to the daily view.
 */
export function DaImbalanceMonthlyGrid({
  data,
  isLoading,
  onDayClick,
}: DaImbalanceMonthlyGridProps) {
  const columns = [
    columnHelper.accessor('deliveryDay', {
      header: 'Delivery Day',
      cell: (info) => {
        const day = info.getValue();
        return onDayClick ? (
          <button
            onClick={() => onDayClick(day)}
            className="text-interactive-primary hover:underline text-left font-medium"
          >
            {day}
          </button>
        ) : (
          <span className="text-text-primary font-medium">{day}</span>
        );
      },
      size: 120,
    }),
    columnHelper.accessor('netImbalanceMwh', {
      header: 'Net Imbalance (MWh)',
      cell: (info) => <NumericCell value={info.getValue()} precision="MWH" showSign />,
      size: 150,
    }),
    columnHelper.accessor('netCost', {
      header: 'Net Cost',
      cell: (info) => (
        <NumericCell
          value={info.getValue()}
          precision="MONETARY"
          currency={info.row.original.currency}
          showSign
        />
      ),
      size: 130,
    }),
    columnHelper.accessor('maxDeviationMw', {
      header: 'Max Deviation (MW)',
      cell: (info) => <NumericCell value={info.getValue()} precision="MW" showSign />,
      size: 140,
    }),
    columnHelper.accessor('intervalsWithImbalance', {
      header: 'Intervals w/ Imbalance',
      cell: (info) => (
        <span className="text-text-primary">
          {info.getValue()} / {info.row.original.totalIntervals}
        </span>
      ),
      size: 160,
    }),
  ];

  const rows = data ?? [];

  const table = useReactTable({
    data: rows,
    columns,
    getCoreRowModel: getCoreRowModel(),
    getRowId: (row) => row.deliveryDay,
  });

  if (isLoading) {
    return <SkeletonTable rows={10} columns={[12, 15, 13, 14, 16]} />;
  }

  if (rows.length === 0) {
    return <EmptyState message="No imbalance data for this month." />;
  }

  return (
    <div className="overflow-auto border border-border-grid rounded max-h-[600px]">
      <table
        className="w-full border-collapse"
        role="grid"
        aria-label="Monthly imbalance summary"
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
