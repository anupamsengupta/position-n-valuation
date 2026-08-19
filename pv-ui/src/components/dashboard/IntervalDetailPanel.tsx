import { useState, useCallback, useRef, useEffect, useMemo } from 'react';
import { SubGranularityToggle } from '@/components/primitives/GranularityToggle';
import { ViewLayoutToggle, type ViewLayout } from '@/components/primitives/ViewLayoutToggle';
import { SettledDayGrid } from './SettledDayGrid';
import { ForwardDayGrid } from './ForwardDayGrid';
import { HorizontalSettledGrid } from './HorizontalSettledGrid';
import { HorizontalForwardGrid } from './HorizontalForwardGrid';
import { MonthViewGrid } from './MonthViewGrid';
import { NettedViewBanner } from './NettedViewBanner';
import {
  useSettledDayDetail,
  useForwardDayDetail,
  useDailyAggregates,
} from '@/hooks/useDashboardQueries';
import { useDashboardSelection } from '@/hooks/useDashboardSelection';
import { useUserPreferences } from '@/hooks/useUserPreferences';
import { formatLocalDate } from '@/lib/dateUtils';
import type { SubDailyGranularity } from '@/schemas/types';
import type { DailyAggregateDto } from '@/schemas/api';

export interface IntervalDetailPanelProps {
  portfolioId: string;
  periodStart: string;
  periodEnd: string;
  positionIds: string[];
  selectedTradeIds: string[];
  onClose: () => void;
  onClearSelection: () => void;
}

/**
 * L4: Interval detail panel with month view, settled day, and forward day grids.
 * Supports multi-position netted views (F1) and multi-day ranges (F2).
 */
export function IntervalDetailPanel({
  portfolioId,
  periodStart,
  periodEnd,
  positionIds,
  selectedTradeIds,
  onClose,
  onClearSelection,
}: IntervalDetailPanelProps) {
  const sectionRef = useRef<HTMLElement>(null);
  const timezone = useUserPreferences((s) => s.timezone);
  const {
    selectedDay,
    selectedDayEnd,
    selectedDayRange,
    selectedDayStatus,
    setSelectedDay,
    setDayRange,
    clearDayRange,
  } = useDashboardSelection();

  useEffect(() => {
    sectionRef.current?.focus();
  }, []);

  const [subGranularity, setSubGranularity] = useState<SubDailyGranularity>('MIN_15');
  const [viewLayout, setViewLayout] = useState<ViewLayout>('vertical');

  const isNetted = positionIds.length >= 2;
  const isMultiDay = selectedDayRange.size > 1;

  // Fetch daily aggregates for the month view
  const dailyQuery = useDailyAggregates(
    portfolioId,
    periodStart,
    periodEnd,
    positionIds,
  );

  // Find the selected day's aggregate to get dayEnd and intervalCount
  const selectedDayAggregate = dailyQuery.data?.find(
    (d) => d.dayStart === selectedDay,
  );
  // For multi-day: find the last day's aggregate
  const selectedDayEndAggregate = selectedDayEnd
    ? dailyQuery.data?.find((d) => d.dayStart === selectedDayEnd)
    : selectedDayAggregate;

  // Determine dayStart and dayEnd for sub-daily queries
  const subDayStart = selectedDay;
  const subDayEnd = selectedDayEndAggregate?.dayEnd ?? selectedDayAggregate?.dayEnd ?? null;

  // Fetch sub-daily data when a day (or range) is selected
  const settledQuery = useSettledDayDetail(
    portfolioId,
    selectedDayStatus === 'SETTLED' || selectedDayStatus === 'TODAY' ? subDayStart ?? undefined : undefined,
    selectedDayStatus === 'SETTLED' || selectedDayStatus === 'TODAY' ? subDayEnd ?? undefined : undefined,
    subGranularity,
    positionIds,
  );

  const forwardQuery = useForwardDayDetail(
    portfolioId,
    selectedDayStatus === 'FORWARD' ? subDayStart ?? undefined : undefined,
    selectedDayStatus === 'FORWARD' ? subDayEnd ?? undefined : undefined,
    subGranularity,
    positionIds,
  );

  const handleDayClick = useCallback(
    (row: DailyAggregateDto) => {
      if (selectedDay === row.dayStart && selectedDayRange.size <= 1) {
        setSelectedDay(null, null);
      } else {
        const status = row.dayStatus as 'SETTLED' | 'TODAY' | 'FORWARD';
        setSelectedDay(row.dayStart, status);
      }
    },
    [selectedDay, selectedDayRange.size, setSelectedDay],
  );

  const handleDayShiftClick = useCallback(
    (row: DailyAggregateDto) => {
      if (!selectedDay || !dailyQuery.data) return;
      setDayRange(selectedDay, row.dayStart, dailyQuery.data);
    },
    [selectedDay, dailyQuery.data, setDayRange],
  );

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        if (isMultiDay) {
          clearDayRange();
        } else if (selectedDay) {
          setSelectedDay(null, null);
        } else {
          onClose();
        }
      }
    },
    [selectedDay, isMultiDay, setSelectedDay, clearDayRange, onClose],
  );

  // Header text
  const headerInfo = useMemo(() => {
    if (isNetted) {
      return `Netted view: ${positionIds.length} positions`;
    }
    if (positionIds.length === 1) {
      return `Position: ${positionIds[0]!.substring(0, 8)}...`;
    }
    return null;
  }, [isNetted, positionIds]);

  // Day range label
  const dayRangeLabel = useMemo(() => {
    if (!selectedDay) return '';
    if (isMultiDay && selectedDayEnd) {
      return `${formatLocalDate(selectedDay, timezone)} \u2013 ${formatLocalDate(selectedDayEnd, timezone)}`;
    }
    return formatLocalDate(selectedDay, timezone);
  }, [selectedDay, selectedDayEnd, isMultiDay, timezone]);

  // Expected interval count for sub-daily grids
  const expectedIntervalCount = useMemo(() => {
    if (!dailyQuery.data || selectedDayRange.size === 0) return undefined;
    let total = 0;
    for (const d of dailyQuery.data) {
      if (selectedDayRange.has(d.dayStart)) {
        total += d.intervalCount;
      }
    }
    return total || undefined;
  }, [dailyQuery.data, selectedDayRange]);

  return (
    <section
      aria-label="Interval Detail"
      ref={sectionRef}
      tabIndex={-1}
      onKeyDown={handleKeyDown}
    >
      <div className="flex items-center justify-between mb-3">
        <h3 className="text-sm font-semibold text-text-primary">
          Interval Detail
          {headerInfo && (
            <span className="ml-2 text-xs text-text-muted font-normal">
              {headerInfo}
            </span>
          )}
        </h3>
        <button
          type="button"
          onClick={onClose}
          className="px-2 py-1 text-xs text-text-secondary hover:text-text-primary
                     border border-border-default rounded hover:bg-bg-secondary
                     focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus"
          aria-label="Close interval detail"
        >
          Close
        </button>
      </div>

      {/* Netted view banner for multi-select */}
      {isNetted && (
        <div className="mb-3">
          <NettedViewBanner
            selectedCount={positionIds.length}
            selectedTradeIds={selectedTradeIds}
            onClear={onClearSelection}
          />
        </div>
      )}

      {/* Month view: always show daily aggregate rows */}
      <div className="mb-4">
        <h4 className="text-xs font-medium text-text-secondary mb-2">Daily Summary</h4>
        <MonthViewGrid
          data={dailyQuery.data}
          isLoading={dailyQuery.isLoading}
          timezone={timezone}
          selectedDay={selectedDay}
          selectedDayRange={selectedDayRange}
          onDayClick={handleDayClick}
          onDayShiftClick={handleDayShiftClick}
        />
      </div>

      {/* Sub-daily view: show when a day (or range) is selected */}
      {selectedDay && (selectedDayAggregate || isMultiDay) && (
        <div className="mt-4">
          <div className="flex items-center justify-between mb-2">
            <h4 className="text-xs font-medium text-text-secondary">
              {dayRangeLabel} &mdash;{' '}
              {selectedDayStatus === 'SETTLED'
                ? 'Settlement Data'
                : selectedDayStatus === 'TODAY'
                  ? 'Settlement Data (partial day)'
                  : 'Forward Mark Data'}
            </h4>
            <div className="flex items-center gap-3">
              <ViewLayoutToggle value={viewLayout} onChange={setViewLayout} />
              <SubGranularityToggle value={subGranularity} onChange={setSubGranularity} />
            </div>
          </div>

          {(selectedDayStatus === 'SETTLED' || selectedDayStatus === 'TODAY') && (
            viewLayout === 'vertical' ? (
              <SettledDayGrid
                data={settledQuery.data}
                isLoading={settledQuery.isLoading}
                timezone={timezone}
                expectedIntervalCount={expectedIntervalCount}
                isMultiDay={isMultiDay}
              />
            ) : (
              <HorizontalSettledGrid
                data={settledQuery.data}
                isLoading={settledQuery.isLoading}
                timezone={timezone}
                expectedIntervalCount={expectedIntervalCount}
              />
            )
          )}

          {selectedDayStatus === 'FORWARD' && (
            viewLayout === 'vertical' ? (
              <ForwardDayGrid
                data={forwardQuery.data}
                isLoading={forwardQuery.isLoading}
                timezone={timezone}
                expectedIntervalCount={expectedIntervalCount}
                isMultiDay={isMultiDay}
              />
            ) : (
              <HorizontalForwardGrid
                data={forwardQuery.data}
                isLoading={forwardQuery.isLoading}
                timezone={timezone}
                expectedIntervalCount={expectedIntervalCount}
              />
            )
          )}
        </div>
      )}
    </section>
  );
}
