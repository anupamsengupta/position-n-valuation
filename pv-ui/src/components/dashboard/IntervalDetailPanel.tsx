import { useState, useCallback, useRef, useEffect } from 'react';
import { SubGranularityToggle } from '@/components/primitives/GranularityToggle';
import { ViewLayoutToggle, type ViewLayout } from '@/components/primitives/ViewLayoutToggle';
import { SettledDayGrid } from './SettledDayGrid';
import { ForwardDayGrid } from './ForwardDayGrid';
import { HorizontalSettledGrid } from './HorizontalSettledGrid';
import { HorizontalForwardGrid } from './HorizontalForwardGrid';
import { MonthViewGrid } from './MonthViewGrid';
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
  positionId: string | null;
  onClose: () => void;
}

/**
 * L4: Interval detail panel with month view, settled day, and forward day grids.
 * Shows daily aggregates first; clicking a day shows sub-daily intervals.
 */
export function IntervalDetailPanel({
  portfolioId,
  periodStart,
  periodEnd,
  positionId,
  onClose,
}: IntervalDetailPanelProps) {
  const sectionRef = useRef<HTMLElement>(null);
  const timezone = useUserPreferences((s) => s.timezone);
  const { selectedDay, selectedDayStatus, setSelectedDay } = useDashboardSelection();

  // S-2 fix: move focus to section on mount so users discover the new panel
  useEffect(() => {
    sectionRef.current?.focus();
  }, []);
  const [subGranularity, setSubGranularity] = useState<SubDailyGranularity>('MIN_15');
  const [viewLayout, setViewLayout] = useState<ViewLayout>('vertical');

  // Fetch daily aggregates for the month view
  const dailyQuery = useDailyAggregates(
    portfolioId,
    periodStart,
    periodEnd,
    positionId ?? undefined,
  );

  // Find the selected day's aggregate to get dayEnd and intervalCount
  const selectedDayAggregate = dailyQuery.data?.find(
    (d) => d.dayStart === selectedDay,
  );

  // Fetch sub-daily data when a day is selected
  const settledQuery = useSettledDayDetail(
    portfolioId,
    selectedDayStatus === 'SETTLED' || selectedDayStatus === 'TODAY' ? selectedDay ?? undefined : undefined,
    selectedDayAggregate?.dayEnd ?? undefined,
    subGranularity,
    positionId ?? undefined,
  );

  const forwardQuery = useForwardDayDetail(
    portfolioId,
    selectedDayStatus === 'FORWARD' ? selectedDay ?? undefined : undefined,
    selectedDayAggregate?.dayEnd ?? undefined,
    subGranularity,
    positionId ?? undefined,
  );

  const handleDayClick = useCallback(
    (row: DailyAggregateDto) => {
      if (selectedDay === row.dayStart) {
        // Deselect if clicking the same day
        setSelectedDay(null, null);
      } else {
        const status = row.dayStatus as 'SETTLED' | 'TODAY' | 'FORWARD';
        setSelectedDay(row.dayStart, status);
      }
    },
    [selectedDay, setSelectedDay],
  );

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        if (selectedDay) {
          setSelectedDay(null, null);
        } else {
          onClose();
        }
      }
    },
    [selectedDay, setSelectedDay, onClose],
  );

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
          {positionId && (
            <span className="ml-2 text-xs text-text-muted font-normal">
              Position: {positionId.substring(0, 8)}...
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

      {/* Month view: always show daily aggregate rows */}
      <div className="mb-4">
        <h4 className="text-xs font-medium text-text-secondary mb-2">Daily Summary</h4>
        <MonthViewGrid
          data={dailyQuery.data}
          isLoading={dailyQuery.isLoading}
          timezone={timezone}
          selectedDay={selectedDay}
          onDayClick={handleDayClick}
        />
      </div>

      {/* Sub-daily view: show when a day is selected */}
      {selectedDay && selectedDayAggregate && (
        <div className="mt-4">
          <div className="flex items-center justify-between mb-2">
            <h4 className="text-xs font-medium text-text-secondary">
              {formatLocalDate(selectedDay, timezone)} --{' '}
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
                expectedIntervalCount={selectedDayAggregate.intervalCount}
              />
            ) : (
              <HorizontalSettledGrid
                data={settledQuery.data}
                isLoading={settledQuery.isLoading}
                timezone={timezone}
                expectedIntervalCount={selectedDayAggregate.intervalCount}
              />
            )
          )}

          {selectedDayStatus === 'FORWARD' && (
            viewLayout === 'vertical' ? (
              <ForwardDayGrid
                data={forwardQuery.data}
                isLoading={forwardQuery.isLoading}
                timezone={timezone}
                expectedIntervalCount={selectedDayAggregate.intervalCount}
              />
            ) : (
              <HorizontalForwardGrid
                data={forwardQuery.data}
                isLoading={forwardQuery.isLoading}
                timezone={timezone}
                expectedIntervalCount={selectedDayAggregate.intervalCount}
              />
            )
          )}
        </div>
      )}
    </section>
  );
}
