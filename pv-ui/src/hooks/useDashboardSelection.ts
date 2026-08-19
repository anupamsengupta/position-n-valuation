import { create } from 'zustand';
import type { PeriodStatus } from '@/schemas/types';
import type { DailyAggregateDto } from '@/schemas/api';

// Empty set singleton to avoid unnecessary re-renders
const EMPTY_SET: ReadonlySet<string> = new Set<string>();

interface DashboardSelectionState {
  // --- L2 period selection (unchanged) ---
  selectedPeriod: { start: string; end: string; status: PeriodStatus } | null;
  anchorPeriodStart: string | null;
  selectedRangeStarts: string[];

  // --- L3 position selection (F1: multi-select) ---
  selectedPositionIds: ReadonlySet<string>;
  positionAnchorId: string | null;
  activatedPositionId: string | null;

  // --- L4 day selection (F2: day range) ---
  selectedDay: string | null;
  selectedDayEnd: string | null;
  selectedDayRange: ReadonlySet<string>;
  selectedDayStatus: 'SETTLED' | 'TODAY' | 'FORWARD' | null;

  // --- L2 actions (unchanged) ---
  setSelectedPeriod: (period: { start: string; end: string; status: PeriodStatus } | null) => void;
  extendPeriodRange: (
    toRow: { periodStart: string; periodEnd: string; periodStatus: PeriodStatus },
    allRows: { periodStart: string; periodEnd: string; periodStatus: PeriodStatus }[],
  ) => void;
  clearRangeSelection: () => void;

  // --- L3 actions (F1) ---
  /** @deprecated Use setActivatedPosition instead */
  setSelectedPosition: (id: string | null) => void;
  setActivatedPosition: (id: string | null) => void;
  togglePositionSelection: (positionId: string) => void;
  setPositionSelection: (positionIds: ReadonlySet<string>) => void;
  selectAllPositions: (allPositionIds: string[]) => void;
  deselectAllPositions: () => void;
  shiftSelectPosition: (positionId: string, allPositionIds: string[]) => void;

  // --- L4 actions (F2) ---
  setSelectedDay: (day: string | null, status: 'SETTLED' | 'TODAY' | 'FORWARD' | null) => void;
  setDayRange: (
    anchorDay: string,
    endDay: string,
    allDays: Pick<DailyAggregateDto, 'dayStart' | 'dayStatus'>[],
  ) => void;
  clearDayRange: () => void;

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

/** Derive a combined day status from multiple days in a range. */
function deriveCombinedDayStatus(
  days: Pick<DailyAggregateDto, 'dayStatus'>[],
): 'SETTLED' | 'TODAY' | 'FORWARD' | null {
  if (days.length === 0) return null;
  const statuses = new Set(days.map((d) => d.dayStatus));
  if (statuses.size === 1) return days[0]!.dayStatus as 'SETTLED' | 'TODAY' | 'FORWARD';
  // Mixed — use SETTLED as the combined status (has both settled and forward data)
  if (statuses.has('TODAY')) return 'TODAY';
  return 'SETTLED';
}

// Shared cleared-day fragment
const CLEARED_DAY = {
  selectedDay: null,
  selectedDayEnd: null,
  selectedDayRange: EMPTY_SET,
  selectedDayStatus: null,
} as const;

// Shared cleared-position fragment
const CLEARED_POSITION = {
  selectedPositionIds: EMPTY_SET,
  positionAnchorId: null,
  activatedPositionId: null,
} as const;

/**
 * Dashboard selection state for progressive disclosure.
 * Tracks which period, position(s), and day(s) the user has drilled into.
 *
 * Cascade rules (spec S6.1):
 * 1. L2 period change → clears L3 position + L4 day selection
 * 2. L3 position selection change → clears L4 day selection
 * 3. L3 activation change → does NOT clear position selection
 * 4. L4 day selection change → no upward cascade
 */
export const useDashboardSelection = create<DashboardSelectionState>((set) => ({
  selectedPeriod: null,
  anchorPeriodStart: null,
  selectedRangeStarts: [],
  selectedPositionIds: EMPTY_SET,
  positionAnchorId: null,
  activatedPositionId: null,
  selectedDay: null,
  selectedDayEnd: null,
  selectedDayRange: EMPTY_SET,
  selectedDayStatus: null,

  // --- L2 actions ---

  setSelectedPeriod: (period) =>
    set({
      selectedPeriod: period,
      anchorPeriodStart: period?.start ?? null,
      selectedRangeStarts: period ? [period.start] : [],
      // Cascade rule 1: clear L3 + L4
      ...CLEARED_POSITION,
      ...CLEARED_DAY,
    }),

  extendPeriodRange: (toRow, allRows) =>
    set((state) => {
      const anchor = state.anchorPeriodStart;
      if (!anchor) {
        return {
          selectedPeriod: {
            start: toRow.periodStart,
            end: toRow.periodEnd,
            status: toRow.periodStatus,
          },
          anchorPeriodStart: toRow.periodStart,
          selectedRangeStarts: [toRow.periodStart],
          ...CLEARED_POSITION,
          ...CLEARED_DAY,
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
        ...CLEARED_POSITION,
        ...CLEARED_DAY,
      };
    }),

  clearRangeSelection: () =>
    set({
      selectedPeriod: null,
      anchorPeriodStart: null,
      selectedRangeStarts: [],
      ...CLEARED_POSITION,
      ...CLEARED_DAY,
    }),

  // --- L3 actions (F1: multi-select) ---

  setSelectedPosition: (id) =>
    set({
      activatedPositionId: id,
      ...CLEARED_DAY,
    }),

  setActivatedPosition: (id) =>
    set({
      activatedPositionId: id,
      // Cascade rule 3: activation does NOT clear position selection
      ...CLEARED_DAY,
    }),

  togglePositionSelection: (positionId) =>
    set((state) => {
      const next = new Set(state.selectedPositionIds);
      if (next.has(positionId)) {
        next.delete(positionId);
        return {
          selectedPositionIds: next.size === 0 ? EMPTY_SET : next,
          positionAnchorId: null,
          ...CLEARED_DAY,
        };
      } else {
        next.add(positionId);
        return {
          selectedPositionIds: next,
          positionAnchorId: positionId,
          ...CLEARED_DAY,
        };
      }
    }),

  setPositionSelection: (positionIds) =>
    set({
      selectedPositionIds: positionIds.size === 0 ? EMPTY_SET : positionIds,
      ...CLEARED_DAY,
    }),

  selectAllPositions: (allPositionIds) =>
    set({
      selectedPositionIds: new Set(allPositionIds),
      positionAnchorId: null,
      ...CLEARED_DAY,
    }),

  deselectAllPositions: () =>
    set({
      selectedPositionIds: EMPTY_SET,
      positionAnchorId: null,
      ...CLEARED_DAY,
    }),

  shiftSelectPosition: (positionId, allPositionIds) =>
    set((state) => {
      const anchor = state.positionAnchorId;
      if (!anchor) {
        // No anchor — treat as toggle
        const next = new Set(state.selectedPositionIds);
        next.add(positionId);
        return {
          selectedPositionIds: next,
          positionAnchorId: positionId,
          ...CLEARED_DAY,
        };
      }

      const anchorIdx = allPositionIds.indexOf(anchor);
      const targetIdx = allPositionIds.indexOf(positionId);
      if (anchorIdx === -1 || targetIdx === -1) return state;

      const fromIdx = Math.min(anchorIdx, targetIdx);
      const toIdx = Math.max(anchorIdx, targetIdx);
      // Additive range select: merge into existing set
      const next = new Set(state.selectedPositionIds);
      for (let i = fromIdx; i <= toIdx; i++) {
        next.add(allPositionIds[i]!);
      }
      // Anchor stays — do NOT move it
      return {
        selectedPositionIds: next,
        ...CLEARED_DAY,
      };
    }),

  // --- L4 actions (F2: day range) ---

  setSelectedDay: (day, status) =>
    set({
      selectedDay: day,
      selectedDayEnd: null,
      selectedDayRange: day ? new Set([day]) : EMPTY_SET,
      selectedDayStatus: status,
    }),

  setDayRange: (anchorDay, endDay, allDays) =>
    set(() => {
      const anchorIdx = allDays.findIndex((d) => d.dayStart === anchorDay);
      const endIdx = allDays.findIndex((d) => d.dayStart === endDay);
      if (anchorIdx === -1 || endIdx === -1) return {};

      const fromIdx = Math.min(anchorIdx, endIdx);
      let toIdx = Math.max(anchorIdx, endIdx);
      // Clamp to 31 days
      if (toIdx - fromIdx >= 31) {
        toIdx = fromIdx + 30;
      }

      const rangeDays = allDays.slice(fromIdx, toIdx + 1);
      const rangeSet = new Set(rangeDays.map((d) => d.dayStart));
      const combinedStatus = deriveCombinedDayStatus(rangeDays);

      return {
        selectedDay: rangeDays[0]!.dayStart,
        selectedDayEnd: rangeDays[rangeDays.length - 1]!.dayStart,
        selectedDayRange: rangeSet,
        selectedDayStatus: combinedStatus,
      };
    }),

  clearDayRange: () =>
    set(CLEARED_DAY),

  clearAll: () =>
    set((state) => {
      if (
        state.selectedPeriod === null &&
        state.anchorPeriodStart === null &&
        state.selectedRangeStarts.length === 0 &&
        state.selectedPositionIds.size === 0 &&
        state.activatedPositionId === null &&
        state.selectedDay === null &&
        state.selectedDayRange.size === 0
      ) {
        return state;
      }
      return {
        selectedPeriod: null,
        anchorPeriodStart: null,
        selectedRangeStarts: [],
        ...CLEARED_POSITION,
        ...CLEARED_DAY,
      };
    }),
}));
