import { create } from 'zustand';
import type { PeriodStatus } from '@/schemas/types';

interface DashboardSelectionState {
  selectedPeriod: { start: string; end: string; status: PeriodStatus } | null;
  anchorPeriodStart: string | null;
  selectedRangeStarts: string[];
  selectedPositionId: string | null;
  selectedDay: string | null;
  selectedDayStatus: 'SETTLED' | 'TODAY' | 'FORWARD' | null;
  setSelectedPeriod: (period: { start: string; end: string; status: PeriodStatus } | null) => void;
  extendPeriodRange: (
    toRow: { periodStart: string; periodEnd: string; periodStatus: PeriodStatus },
    allRows: { periodStart: string; periodEnd: string; periodStatus: PeriodStatus }[],
  ) => void;
  clearRangeSelection: () => void;
  setSelectedPosition: (id: string | null) => void;
  setSelectedDay: (day: string | null, status: 'SETTLED' | 'TODAY' | 'FORWARD' | null) => void;
  clearAll: () => void;
}

/**
 * Derive a combined status from multiple period statuses.
 * All SETTLED → SETTLED, all FORWARD → FORWARD, any mix → TRANSITION.
 */
export function deriveCombinedStatus(
  rows: { periodStatus: PeriodStatus }[],
): PeriodStatus {
  if (rows.length === 0) return 'FORWARD';
  const first = rows[0];
  if (!first) return 'FORWARD';
  const statuses = new Set(rows.map((r) => r.periodStatus));
  if (statuses.size === 1) return first.periodStatus;
  return 'TRANSITION';
}

/**
 * Dashboard selection state for progressive disclosure.
 * Tracks which period, position, and day the user has drilled into.
 * Supports contiguous range sub-selection via shift+click in the rollup grid.
 */
export const useDashboardSelection = create<DashboardSelectionState>((set) => ({
  selectedPeriod: null,
  anchorPeriodStart: null,
  selectedRangeStarts: [],
  selectedPositionId: null,
  selectedDay: null,
  selectedDayStatus: null,

  setSelectedPeriod: (period) =>
    set({
      selectedPeriod: period,
      anchorPeriodStart: period?.start ?? null,
      selectedRangeStarts: period ? [period.start] : [],
      // Clear child selections when parent changes
      selectedPositionId: null,
      selectedDay: null,
      selectedDayStatus: null,
    }),

  extendPeriodRange: (toRow, allRows) =>
    set((state) => {
      const anchor = state.anchorPeriodStart;
      if (!anchor) {
        // No anchor — treat as single click
        return {
          selectedPeriod: {
            start: toRow.periodStart,
            end: toRow.periodEnd,
            status: toRow.periodStatus,
          },
          anchorPeriodStart: toRow.periodStart,
          selectedRangeStarts: [toRow.periodStart],
          selectedPositionId: null,
          selectedDay: null,
          selectedDayStatus: null,
        };
      }

      const anchorIdx = allRows.findIndex((r) => r.periodStart === anchor);
      const targetIdx = allRows.findIndex((r) => r.periodStart === toRow.periodStart);
      if (anchorIdx === -1 || targetIdx === -1) return state;

      const fromIdx = Math.min(anchorIdx, targetIdx);
      const toIdx = Math.max(anchorIdx, targetIdx);
      const rangeRows = allRows.slice(fromIdx, toIdx + 1);
      if (rangeRows.length === 0) return state;
      const rangeStarts = rangeRows.map((r) => r.periodStart);
      const combinedStatus = deriveCombinedStatus(rangeRows);
      const firstRow = rangeRows[0]!;
      const lastRow = rangeRows[rangeRows.length - 1]!;

      return {
        selectedPeriod: {
          start: firstRow.periodStart,
          end: lastRow.periodEnd,
          status: combinedStatus,
        },
        anchorPeriodStart: anchor,
        selectedRangeStarts: rangeStarts,
        selectedPositionId: null,
        selectedDay: null,
        selectedDayStatus: null,
      };
    }),

  clearRangeSelection: () =>
    set({
      selectedPeriod: null,
      anchorPeriodStart: null,
      selectedRangeStarts: [],
      selectedPositionId: null,
      selectedDay: null,
      selectedDayStatus: null,
    }),

  setSelectedPosition: (id) =>
    set({
      selectedPositionId: id,
      // Clear child selection when position changes
      selectedDay: null,
      selectedDayStatus: null,
    }),

  setSelectedDay: (day, status) =>
    set({
      selectedDay: day,
      selectedDayStatus: status,
    }),

  clearAll: () =>
    set((state) => {
      // Idempotent: avoid new references when state is already cleared
      if (
        state.selectedPeriod === null &&
        state.anchorPeriodStart === null &&
        state.selectedRangeStarts.length === 0 &&
        state.selectedPositionId === null &&
        state.selectedDay === null &&
        state.selectedDayStatus === null
      ) {
        return state;
      }
      return {
        selectedPeriod: null,
        anchorPeriodStart: null,
        selectedRangeStarts: [],
        selectedPositionId: null,
        selectedDay: null,
        selectedDayStatus: null,
      };
    }),
}));
