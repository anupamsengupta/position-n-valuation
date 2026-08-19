import { useState, useCallback, useRef, useEffect, useMemo } from 'react';
import { SubGranularityToggle } from '@/components/primitives/GranularityToggle';
import { ViewLayoutToggle, type ViewLayout } from '@/components/primitives/ViewLayoutToggle';
import { SettledDayGrid } from './SettledDayGrid';
import { ForwardDayGrid } from './ForwardDayGrid';
import { HorizontalSettledGrid } from './HorizontalSettledGrid';
import { HorizontalForwardGrid } from './HorizontalForwardGrid';
import { MonthViewGrid } from './MonthViewGrid';
import { NettedViewBanner } from './NettedViewBanner';
import { DaySelectionBanner } from './DaySelectionBanner';
import {
  useSettledDayDetail,
  useForwardDayDetail,
  useDailyAggregates,
  useMultiDaySettledDetail,
  useMultiDayForwardDetail,
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
 * Supports multi-position netted views (F1), multi-day ranges (F2),
 * and checkbox-based non-contiguous multi-day selection (F4).
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
    selectedDayIds,
    setSelectedDay,
    setDayRange,
    clearDayRange,
    toggleDaySelection,
    shiftSelectDay,
    selectAllDays,
    deselectAllDays,
  } = useDashboardSelection();

  useEffect(() => {
    sectionRef.current?.focus();
  }, []);

  const [subGranularity, setSubGranularity] = useState<SubDailyGranularity>('MIN_15');
  const [viewLayout, setViewLayout] = useState<ViewLayout>('vertical');

  const isNetted = positionIds.length >= 2;
  const isContiguousMultiDay = selectedDayRange.size > 1;
  const hasCheckboxSelection = selectedDayIds.size >= 1;
  const isCheckboxMultiDay = selectedDayIds.size >= 2;

  // Fetch daily aggregates for the month view
  const dailyQuery = useDailyAggregates(
    portfolioId,
    periodStart,
    periodEnd,
    positionIds,
  );

  // Compute allDayStarts for checkbox operations
  const allDayStarts = useMemo(
    () => dailyQuery.data?.map((d) => d.dayStart) ?? [],
    [dailyQuery.data],
  );

  // --- Contiguous range sub-daily queries (F2 / single-day) ---
  const selectedDayAggregate = dailyQuery.data?.find(
    (d) => d.dayStart === selectedDay,
  );
  const selectedDayEndAggregate = selectedDayEnd
    ? dailyQuery.data?.find((d) => d.dayStart === selectedDayEnd)
    : selectedDayAggregate;

  const subDayStart = selectedDay;
  const subDayEnd = selectedDayEndAggregate?.dayEnd ?? selectedDayAggregate?.dayEnd ?? null;

  const settledQuery = useSettledDayDetail(
    portfolioId,
    !hasCheckboxSelection && (selectedDayStatus === 'SETTLED' || selectedDayStatus === 'TODAY') ? subDayStart ?? undefined : undefined,
    !hasCheckboxSelection && (selectedDayStatus === 'SETTLED' || selectedDayStatus === 'TODAY') ? subDayEnd ?? undefined : undefined,
    subGranularity,
    positionIds,
  );

  const forwardQuery = useForwardDayDetail(
    portfolioId,
    !hasCheckboxSelection && selectedDayStatus === 'FORWARD' ? subDayStart ?? undefined : undefined,
    !hasCheckboxSelection && selectedDayStatus === 'FORWARD' ? subDayEnd ?? undefined : undefined,
    subGranularity,
    positionIds,
  );

  // --- Checkbox parallel queries (F4) ---
  const checkboxDayEntries = useMemo(() => {
    if (!hasCheckboxSelection || !dailyQuery.data) return [];
    return dailyQuery.data
      .filter((d) => selectedDayIds.has(d.dayStart))
      .map((d) => ({ dayStart: d.dayStart, dayEnd: d.dayEnd, dayStatus: d.dayStatus }));
  }, [isCheckboxMultiDay, dailyQuery.data, selectedDayIds]);

  // Separate settled vs forward days for checkbox mode
  const settledDayEntries = useMemo(
    () => checkboxDayEntries.filter((d) => d.dayStatus === 'SETTLED' || d.dayStatus === 'TODAY'),
    [checkboxDayEntries],
  );
  const forwardDayEntries = useMemo(
    () => checkboxDayEntries.filter((d) => d.dayStatus === 'FORWARD'),
    [checkboxDayEntries],
  );

  const multiSettledResults = useMultiDaySettledDetail(
    portfolioId,
    hasCheckboxSelection ? settledDayEntries : [],
    subGranularity,
    positionIds,
  );

  const multiForwardResults = useMultiDayForwardDetail(
    portfolioId,
    hasCheckboxSelection ? forwardDayEntries : [],
    subGranularity,
    positionIds,
  );

  // Merge checkbox settled results
  const mergedSettledData = useMemo(() => {
    if (!hasCheckboxSelection || settledDayEntries.length === 0) return undefined;
    return multiSettledResults
      .flatMap((q) => q.data ?? [])
      .sort((a, b) => a.intervalStart.localeCompare(b.intervalStart));
  }, [hasCheckboxSelection, settledDayEntries.length, multiSettledResults]);

  // Merge checkbox forward results
  const mergedForwardData = useMemo(() => {
    if (!hasCheckboxSelection || forwardDayEntries.length === 0) return undefined;
    return multiForwardResults
      .flatMap((q) => q.data ?? [])
      .sort((a, b) => a.intervalStart.localeCompare(b.intervalStart));
  }, [hasCheckboxSelection, forwardDayEntries.length, multiForwardResults]);

  const multiSettledLoading = multiSettledResults.some((q) => q.isLoading);
  const multiForwardLoading = multiForwardResults.some((q) => q.isLoading);

  // --- Handlers ---

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

  const handleDayToggle = useCallback(
    (row: DailyAggregateDto) => {
      toggleDaySelection(row.dayStart);
    },
    [toggleDaySelection],
  );

  const handleDayShiftToggle = useCallback(
    (row: DailyAggregateDto) => {
      shiftSelectDay(row.dayStart, allDayStarts);
    },
    [shiftSelectDay, allDayStarts],
  );

  const handleSelectAllDays = useCallback(() => {
    selectAllDays(allDayStarts);
  }, [selectAllDays, allDayStarts]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        if (hasCheckboxSelection) {
          deselectAllDays();
        } else if (isContiguousMultiDay) {
          clearDayRange();
        } else if (selectedDay) {
          setSelectedDay(null, null);
        } else {
          onClose();
        }
      }
    },
    [selectedDay, isContiguousMultiDay, hasCheckboxSelection, setSelectedDay, clearDayRange, deselectAllDays, onClose],
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

  // Day range label (for contiguous mode)
  const dayRangeLabel = useMemo(() => {
    if (!selectedDay) return '';
    if (isContiguousMultiDay && selectedDayEnd) {
      return `${formatLocalDate(selectedDay, timezone)} \u2013 ${formatLocalDate(selectedDayEnd, timezone)}`;
    }
    return formatLocalDate(selectedDay, timezone);
  }, [selectedDay, selectedDayEnd, isContiguousMultiDay, timezone]);

  // Day labels for checkbox banner
  const checkboxDayLabels = useMemo(() => {
    if (!hasCheckboxSelection || !dailyQuery.data) return [];
    return dailyQuery.data
      .filter((d) => selectedDayIds.has(d.dayStart))
      .map((d) => formatLocalDate(d.dayStart, timezone));
  }, [hasCheckboxSelection, dailyQuery.data, selectedDayIds, timezone]);

  // Expected interval count for sub-daily grids
  const expectedIntervalCount = useMemo(() => {
    if (!dailyQuery.data) return undefined;
    if (hasCheckboxSelection) {
      let total = 0;
      for (const d of dailyQuery.data) {
        if (selectedDayIds.has(d.dayStart)) total += d.intervalCount;
      }
      return total || undefined;
    }
    if (selectedDayRange.size === 0) return undefined;
    let total = 0;
    for (const d of dailyQuery.data) {
      if (selectedDayRange.has(d.dayStart)) total += d.intervalCount;
    }
    return total || undefined;
  }, [dailyQuery.data, selectedDayRange, selectedDayIds, hasCheckboxSelection]);

  // Determine what sub-daily views to show
  const showContiguousSubDaily = !hasCheckboxSelection && selectedDay && (selectedDayAggregate || isContiguousMultiDay);
  const showCheckboxSubDaily = hasCheckboxSelection;
  const hasSettledDays = settledDayEntries.length > 0;
  const hasForwardDays = forwardDayEntries.length > 0;

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

      {/* Netted view banner for multi-position */}
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
          selectedDayIds={selectedDayIds}
          onDayToggle={handleDayToggle}
          onDayShiftToggle={handleDayShiftToggle}
          onSelectAllDays={handleSelectAllDays}
          onDeselectAllDays={deselectAllDays}
        />
      </div>

      {/* Checkbox multi-day sub-daily view (F4) */}
      {showCheckboxSubDaily && (
        <div className="mt-4">
          {isCheckboxMultiDay && (
            <DaySelectionBanner
              selectedCount={selectedDayIds.size}
              dayLabels={checkboxDayLabels}
              onClear={deselectAllDays}
            />
          )}

          <div className="flex items-center justify-between mb-2 mt-3">
            <h4 className="text-xs font-medium text-text-secondary">
              {hasSettledDays && hasForwardDays
                ? 'Settlement & Forward Mark Data'
                : hasSettledDays
                  ? `Settlement Data (${settledDayEntries.length} days)`
                  : `Forward Mark Data (${forwardDayEntries.length} days)`}
            </h4>
            <div className="flex items-center gap-3">
              <ViewLayoutToggle value={viewLayout} onChange={setViewLayout} />
              <SubGranularityToggle value={subGranularity} onChange={setSubGranularity} />
            </div>
          </div>

          {hasSettledDays && (
            <>
              {hasForwardDays && (
                <h5 className="text-[10px] font-medium text-text-muted mb-1">
                  Settlement Data ({settledDayEntries.length} days)
                </h5>
              )}
              {viewLayout === 'vertical' ? (
                <SettledDayGrid
                  data={mergedSettledData}
                  isLoading={multiSettledLoading}
                  timezone={timezone}
                  expectedIntervalCount={expectedIntervalCount}
                  isMultiDay={true}
                />
              ) : (
                <HorizontalSettledGrid
                  data={mergedSettledData}
                  isLoading={multiSettledLoading}
                  timezone={timezone}
                  expectedIntervalCount={expectedIntervalCount}
                />
              )}
            </>
          )}

          {hasForwardDays && (
            <>
              {hasSettledDays && (
                <h5 className="text-[10px] font-medium text-text-muted mb-1 mt-3">
                  Forward Mark Data ({forwardDayEntries.length} days)
                </h5>
              )}
              {viewLayout === 'vertical' ? (
                <ForwardDayGrid
                  data={mergedForwardData}
                  isLoading={multiForwardLoading}
                  timezone={timezone}
                  expectedIntervalCount={expectedIntervalCount}
                  isMultiDay={true}
                />
              ) : (
                <HorizontalForwardGrid
                  data={mergedForwardData}
                  isLoading={multiForwardLoading}
                  timezone={timezone}
                  expectedIntervalCount={expectedIntervalCount}
                />
              )}
            </>
          )}
        </div>
      )}

      {/* Contiguous range / single-day sub-daily view (F2 / existing) */}
      {showContiguousSubDaily && (
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
                isMultiDay={isContiguousMultiDay}
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
                isMultiDay={isContiguousMultiDay}
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
