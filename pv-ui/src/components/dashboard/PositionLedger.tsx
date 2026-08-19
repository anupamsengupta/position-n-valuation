import { useMemo, useCallback, useRef, useEffect, useState } from 'react';
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
import { SelectAllCheckbox } from '@/components/primitives/SelectAllCheckbox';
import { RowCheckbox } from '@/components/primitives/RowCheckbox';
import { HideZeroToggle } from '@/components/primitives/HideZeroToggle';
import { cn } from '@/lib/cn';
import { parseNumericValue } from '@/lib/numberUtils';
import type { PositionContributionDto } from '@/schemas/api';
import type { PeriodStatus } from '@/schemas/types';

export interface PositionLedgerProps {
  data: PositionContributionDto[] | undefined;
  isLoading: boolean;
  periodLabel: string;
  periodStatus: PeriodStatus;
  selectedPositionIds: ReadonlySet<string>;
  activatedPositionId: string | null;
  onRowActivate: (row: PositionContributionDto) => void;
  onSelectionChange: (selectedIds: ReadonlySet<string>) => void;
  onClose: () => void;
}

const columnHelper = createColumnHelper<PositionContributionDto>();

/**
 * L3: Position ledger table with multi-select checkboxes (F1).
 *
 * Separation of concerns:
 * - "Selection" (checkboxes) = multi-select for netted L4 view
 * - "Activation" (Enter/row click) = drill into single position L4 detail
 */
export function PositionLedger({
  data,
  isLoading,
  periodLabel,
  periodStatus,
  selectedPositionIds,
  activatedPositionId,
  onRowActivate,
  onSelectionChange,
  onClose,
}: PositionLedgerProps) {
  const sectionRef = useRef<HTMLElement>(null);
  const rowRefs = useRef<(HTMLTableRowElement | null)[]>([]);
  const [anchorIndex, setAnchorIndex] = useState<number | null>(null);
  const [hideZeroRows, setHideZeroRows] = useState(false);

  useEffect(() => {
    sectionRef.current?.focus();
  }, []);

  const LEDGER_MEASURE_FIELDS: (keyof PositionContributionDto)[] = [
    'settledMw', 'settledMwh', 'avgPrice', 'realizedPnl', 'forwardMw', 'forwardMwh', 'unrealizedMtm',
  ];

  const filteredData = useMemo(() => {
    if (!data || !hideZeroRows) return data;
    return data.filter((row) =>
      LEDGER_MEASURE_FIELDS.some((f) => {
        const v = parseNumericValue(row[f] as string | number | null | undefined);
        return v !== null && v !== 0;
      }),
    );
  }, [data, hideZeroRows]);

  const allPositionIds = useMemo(
    () => (filteredData ?? []).map((r) => r.positionId).filter((id): id is string => id != null),
    [filteredData],
  );

  const allSelected = allPositionIds.length > 0 && allPositionIds.every((id) => selectedPositionIds.has(id));
  const someSelected = allPositionIds.some((id) => selectedPositionIds.has(id));

  const handleSelectAll = useCallback(() => {
    if (allSelected) {
      onSelectionChange(new Set());
    } else {
      onSelectionChange(new Set(allPositionIds));
    }
    setAnchorIndex(null);
  }, [allSelected, allPositionIds, onSelectionChange]);

  const handleToggle = useCallback(
    (positionId: string, rowIndex: number) => {
      const next = new Set(selectedPositionIds);
      if (next.has(positionId)) {
        next.delete(positionId);
      } else {
        next.add(positionId);
      }
      onSelectionChange(next);
      setAnchorIndex(rowIndex);
    },
    [selectedPositionIds, onSelectionChange],
  );

  const handleShiftSelect = useCallback(
    (rowIndex: number) => {
      const anchor = anchorIndex ?? 0;
      const from = Math.min(anchor, rowIndex);
      const to = Math.max(anchor, rowIndex);
      const next = new Set(selectedPositionIds);
      for (let i = from; i <= to; i++) {
        const id = allPositionIds[i];
        if (id) next.add(id);
      }
      onSelectionChange(next);
    },
    [anchorIndex, allPositionIds, selectedPositionIds, onSelectionChange],
  );

  const columns = useMemo(
    () => [
      columnHelper.display({
        id: 'select',
        header: () => (
          <SelectAllCheckbox
            checked={allSelected}
            indeterminate={someSelected && !allSelected}
            onChange={handleSelectAll}
            totalCount={allPositionIds.length}
            selectedCount={selectedPositionIds.size}
          />
        ),
        cell: ({ row }) => {
          const posId = row.original.positionId;
          if (!posId) return null;
          return (
            <RowCheckbox
              checked={selectedPositionIds.has(posId)}
              onChange={() => handleToggle(posId, row.index)}
              label={`Select position ${row.original.tradeId} leg ${row.original.tradeLegId}`}
            />
          );
        },
        size: 28,
      }),
      columnHelper.accessor('tradeId', {
        header: 'Trade ID',
        cell: (info) => <span className="font-medium text-text-primary">{info.getValue()}</span>,
        size: 100,
      }),
      columnHelper.accessor('tradeLegId', {
        header: 'Leg',
        cell: (info) => <span className="text-text-secondary">{info.getValue()}</span>,
        size: 60,
      }),
      columnHelper.accessor('deliveryStart', {
        header: 'Start',
        cell: (info) => {
          const val = info.getValue();
          if (!val) return <span className="text-text-muted">—</span>;
          return <span className="text-text-secondary">{new Date(val).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' })}</span>;
        },
        size: 100,
      }),
      columnHelper.accessor('deliveryEnd', {
        header: 'End',
        cell: (info) => {
          const val = info.getValue();
          if (!val) return <span className="text-text-muted">—</span>;
          return <span className="text-text-secondary">{new Date(val).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' })}</span>;
        },
        size: 100,
      }),
      columnHelper.accessor('deliveryStatus', {
        header: 'Status',
        cell: (info) => {
          const val = info.getValue();
          if (val === 'SETTLED' || val === 'PARTIAL' || val === 'FORWARD') {
            return <StatusBadge status={val} />;
          }
          return <span className="text-text-muted text-xs">{val}</span>;
        },
        size: 90,
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
      columnHelper.accessor('avgPrice', {
        header: 'Avg Price',
        cell: (info) => <NumericCell value={info.getValue()} precision="PRICE" />,
        size: 100,
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
      columnHelper.accessor('unrealizedMtm', {
        header: 'Unrealized MtM (indicative)',
        cell: (info) => (
          <NumericCell
            value={info.getValue()}
            precision="MONETARY"
            currency={info.row.original.currency}
            showSign
          />
        ),
        size: 170,
      }),
    ],
    [allSelected, someSelected, handleSelectAll, allPositionIds.length, selectedPositionIds, handleToggle],
  );

  const table = useReactTable({
    data: filteredData ?? [],
    columns,
    getCoreRowModel: getCoreRowModel(),
    getRowId: (row) => row.positionId ?? `${row.tradeId}-${row.tradeLegId}`,
  });

  const handleRowClick = useCallback(
    (row: PositionContributionDto) => {
      onRowActivate(row);
    },
    [onRowActivate],
  );

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLTableRowElement>, row: PositionContributionDto, rowIndex: number) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        handleRowClick(row);
      } else if (e.key === ' ') {
        e.preventDefault();
        if (row.positionId) {
          if (e.shiftKey) {
            handleShiftSelect(rowIndex);
          } else {
            handleToggle(row.positionId, rowIndex);
          }
        }
      } else if (e.key === 'ArrowDown') {
        e.preventDefault();
        rowRefs.current[rowIndex + 1]?.focus();
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        rowRefs.current[rowIndex - 1]?.focus();
      } else if (e.key === 'Escape') {
        e.preventDefault();
        if (selectedPositionIds.size > 0) {
          onSelectionChange(new Set());
        } else {
          onClose();
        }
      } else if (e.key === 'a' && (e.ctrlKey || e.metaKey)) {
        e.preventDefault();
        handleSelectAll();
      }
    },
    [handleRowClick, handleShiftSelect, handleToggle, handleSelectAll, selectedPositionIds.size, onSelectionChange, onClose],
  );

  const statusBadgeStatus = useMemo(() => {
    if (periodStatus === 'TRANSITION') return 'TRANSITION' as const;
    if (periodStatus === 'SETTLED') return 'SETTLED' as const;
    return 'FORWARD' as const;
  }, [periodStatus]);

  const focusableRowIndex = useMemo(() => {
    if (activatedPositionId && filteredData) {
      const idx = filteredData.findIndex((r) => r.positionId === activatedPositionId);
      if (idx >= 0) return idx;
    }
    return 0;
  }, [filteredData, activatedPositionId]);

  // Live region text for screen reader announcements
  const liveText = useMemo(() => {
    if (selectedPositionIds.size === 0) return '';
    if (allSelected) return `All ${allPositionIds.length} positions selected`;
    return `${selectedPositionIds.size} of ${allPositionIds.length} positions selected`;
  }, [selectedPositionIds.size, allSelected, allPositionIds.length]);

  return (
    <section aria-label="Position Ledger" ref={sectionRef} tabIndex={-1}>
      <div className="flex items-center justify-between mb-2">
        <div className="flex items-center gap-2">
          <h3 className="text-sm font-semibold text-text-primary">
            Position Contributions
          </h3>
          <span className="text-xs text-text-secondary">{periodLabel}</span>
          <StatusBadge status={statusBadgeStatus} />
        </div>
        <div className="flex items-center gap-3">
          <HideZeroToggle checked={hideZeroRows} onChange={setHideZeroRows} />
          <button
            type="button"
            onClick={onClose}
            className="px-2 py-1 text-xs text-text-secondary hover:text-text-primary
                       border border-border-default rounded hover:bg-bg-secondary
                       focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus"
            aria-label="Close position ledger"
          >
            Close
          </button>
        </div>
      </div>

      {/* Live region for selection announcements */}
      <div className="sr-only" role="status" aria-live="polite" aria-atomic="true">
        {liveText}
      </div>

      {isLoading ? (
        <SkeletonTable rows={8} columns={[3, 10, 6, 10, 10, 8, 10, 11, 10, 12, 9, 10, 14]} />
      ) : !filteredData || filteredData.length === 0 ? (
        <EmptyState message={hideZeroRows ? "All rows are zero — toggle off to see data." : "No position contributions for this period."} />
      ) : (
        <div
          className="overflow-auto border border-border-grid rounded"
          role="grid"
          aria-label="Position contributions"
          aria-multiselectable="true"
          aria-rowcount={filteredData.length}
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
                const posId = row.original.positionId;
                const isSelected = posId != null && selectedPositionIds.has(posId);
                const isActivated = posId != null && posId === activatedPositionId;
                return (
                  <tr
                    key={row.id}
                    ref={(el) => { rowRefs.current[rowIndex] = el; }}
                    role="row"
                    tabIndex={rowIndex === focusableRowIndex ? 0 : -1}
                    aria-selected={isSelected}
                    aria-rowindex={rowIndex + 1}
                    className={cn(
                      'h-6 cursor-pointer transition-colors',
                      'hover:bg-interactive-row-hover',
                      'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-interactive-focus',
                      isSelected && 'bg-interactive-row-selected',
                      isActivated && !isSelected && 'bg-interactive-row-hover',
                      isActivated && 'border-l-2 border-l-interactive-focus',
                      rowIndex % 2 === 1 && !isSelected && 'bg-bg-grid-even',
                    )}
                    onClick={() => handleRowClick(row.original)}
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
      )}
    </section>
  );
}
