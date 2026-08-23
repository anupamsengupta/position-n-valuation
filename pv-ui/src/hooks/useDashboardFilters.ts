import { create } from 'zustand';
import type { TimeGranularity } from '@/schemas/types';
import {
  getMonthRange,
  getYearRange,
  getCurrentQuarterRange,
  adjustRangeForGranularity,
} from '@/lib/dateUtils';
import { useDashboardSelection } from './useDashboardSelection';

type DateRange = { rangeStart: string; rangeEnd: string };

export type QuickFilterPreset = 'THIS_MONTH' | 'THIS_QUARTER' | 'THIS_YEAR' | 'NEXT_YEAR';

interface DashboardFiltersState {
  portfolioId: string;
  granularity: TimeGranularity;
  dateRange: DateRange;
  setPortfolioId: (id: string) => void;
  setGranularity: (g: TimeGranularity) => void;
  setDateRange: (range: DateRange) => void;
  applyQuickFilter: (preset: QuickFilterPreset) => void;
}

function defaultDateRange(): DateRange {
  const now = new Date();
  return getYearRange(now.getFullYear());
}

function clearDrillDown() {
  useDashboardSelection.getState().clearAll();
}

export const useDashboardFilters = create<DashboardFiltersState>((set, get) => ({
  portfolioId: '',
  granularity: 'MONTHLY',
  dateRange: defaultDateRange(),

  setPortfolioId: (id) => {
    if (id === get().portfolioId) return;
    clearDrillDown();
    set({ portfolioId: id });
  },

  setGranularity: (g) => {
    clearDrillDown();
    set((state) => ({
      granularity: g,
      dateRange: adjustRangeForGranularity(state.dateRange, g),
    }));
  },

  setDateRange: (range) => {
    clearDrillDown();
    set({ dateRange: range });
  },

  applyQuickFilter: (preset) => {
    clearDrillDown();
    const now = new Date();
    const year = now.getFullYear();
    const month = now.getMonth() + 1;

    switch (preset) {
      case 'THIS_MONTH':
        set({ granularity: 'DAILY', dateRange: getMonthRange(year, month) });
        break;
      case 'THIS_QUARTER':
        set({ granularity: 'MONTHLY', dateRange: getCurrentQuarterRange() });
        break;
      case 'THIS_YEAR':
        set({ granularity: 'MONTHLY', dateRange: getYearRange(year) });
        break;
      case 'NEXT_YEAR':
        set({ granularity: 'MONTHLY', dateRange: getYearRange(year + 1) });
        break;
    }
  },
}));
