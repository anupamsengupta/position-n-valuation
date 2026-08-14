import { create } from 'zustand';
import type { PeriodStatus } from '@/schemas/types';

interface DashboardSelectionState {
  selectedPeriod: { start: string; end: string; status: PeriodStatus } | null;
  selectedPositionId: string | null;
  selectedDay: string | null;
  selectedDayStatus: 'SETTLED' | 'TODAY' | 'FORWARD' | null;
  setSelectedPeriod: (period: { start: string; end: string; status: PeriodStatus } | null) => void;
  setSelectedPosition: (id: string | null) => void;
  setSelectedDay: (day: string | null, status: 'SETTLED' | 'TODAY' | 'FORWARD' | null) => void;
  clearAll: () => void;
}

/**
 * Dashboard selection state for progressive disclosure.
 * Tracks which period, position, and day the user has drilled into.
 */
export const useDashboardSelection = create<DashboardSelectionState>((set) => ({
  selectedPeriod: null,
  selectedPositionId: null,
  selectedDay: null,
  selectedDayStatus: null,

  setSelectedPeriod: (period) =>
    set({
      selectedPeriod: period,
      // Clear child selections when parent changes
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
    set({
      selectedPeriod: null,
      selectedPositionId: null,
      selectedDay: null,
      selectedDayStatus: null,
    }),
}));
