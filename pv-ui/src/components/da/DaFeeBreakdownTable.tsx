import { useMemo } from 'react';
import {
  createColumnHelper,
  flexRender,
  getCoreRowModel,
  useReactTable,
} from '@tanstack/react-table';
import { NumericCell } from '@/components/primitives/NumericCell';
import { cn } from '@/lib/cn';
import type { FeeLineItemDto, DaFeeBreakdownDto } from '@/schemas/daApi';
import { parseNumericValue } from '@/lib/numberUtils';

export interface DaFeeBreakdownTableProps {
  data: DaFeeBreakdownDto;
}

const columnHelper = createColumnHelper<FeeLineItemDto>();

const columns = [
  columnHelper.accessor('feeType', {
    header: 'Fee Type',
    cell: (info) => (
      <span className="text-text-primary font-medium">{info.getValue()}</span>
    ),
    size: 200,
  }),
  columnHelper.accessor('ratePerMwh', {
    header: 'Rate (EUR/MWh)',
    cell: (info) => <NumericCell value={info.getValue()} precision="PRICE" />,
    size: 140,
  }),
  columnHelper.accessor('grossVolumeMwh', {
    header: 'Gross Volume (MWh)',
    cell: (info) => <NumericCell value={info.getValue()} precision="MWH" />,
    size: 160,
  }),
  columnHelper.accessor('feeAmount', {
    header: 'Fee Amount (EUR)',
    cell: (info) => (
      <NumericCell
        value={info.getValue()}
        precision="MONETARY"
        currency={info.row.original.currency}
      />
    ),
    size: 160,
  }),
];

/**
 * Fee breakdown table with footer totals row.
 * Displays fee line items from the exchange fee schedule.
 */
export function DaFeeBreakdownTable({ data }: DaFeeBreakdownTableProps) {
  const totalFeeAmount = useMemo(
    () => parseNumericValue(data.totalFeeAmount),
    [data.totalFeeAmount],
  );
  const grossVolume = useMemo(
    () => parseNumericValue(data.grossVolumeMwh),
    [data.grossVolumeMwh],
  );

  const table = useReactTable({
    data: data.items,
    columns,
    getCoreRowModel: getCoreRowModel(),
    getRowId: (row) => row.feeType,
  });

  return (
    <div className="overflow-auto border border-border-grid rounded">
      <table className="w-full border-collapse" aria-label="Fee breakdown">
        <thead className="sticky top-0 z-10 bg-bg-secondary">
          {table.getHeaderGroups().map((headerGroup) => (
            <tr key={headerGroup.id} className="h-7">
              {headerGroup.headers.map((header) => (
                <th
                  key={header.id}
                  className={cn(
                    'px-2 py-1 text-xs font-semibold text-text-secondary whitespace-nowrap border-b border-border-grid',
                    header.index === 0 ? 'text-left' : 'text-right',
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
              {row.getVisibleCells().map((cell, cellIndex) => (
                <td
                  key={cell.id}
                  className={cn(
                    'px-2 py-0.5 text-xs whitespace-nowrap',
                    cellIndex > 0 && 'text-right',
                  )}
                >
                  {flexRender(cell.column.columnDef.cell, cell.getContext())}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr className="h-7 border-t-2 border-border-grid bg-bg-secondary font-semibold">
            <td className="px-2 py-1 text-xs text-text-primary">Total</td>
            <td className="px-2 py-1 text-xs text-right" />
            <td className="px-2 py-1 text-xs text-right">
              <NumericCell value={grossVolume} precision="MWH" />
            </td>
            <td className="px-2 py-1 text-xs text-right">
              <NumericCell
                value={totalFeeAmount}
                precision="MONETARY"
                currency={data.currency}
              />
            </td>
          </tr>
        </tfoot>
      </table>
    </div>
  );
}
