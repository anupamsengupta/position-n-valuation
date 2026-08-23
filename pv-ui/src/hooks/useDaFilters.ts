import { create } from 'zustand';

/** Filter subset used as query key for import history. */
export interface DaImportFilters {
  status: string | null;
  zone: string | null;
  dateFrom: string | null;
  dateTo: string | null;
}

/** Filter subset used as query key for alerts. */
export interface DaAlertFilters {
  status: string | null;
  severity: string[];
  category: string | null;
  dateFrom: string | null;
  dateTo: string | null;
}

interface DaFiltersState {
  // Shared across DA pages
  deliveryDay: string; // YYYY-MM-DD, default today
  zone: string; // default 'DE_LU'
  balancingGroupId: string | null; // null = "All"
  yearMonth: string; // YYYY-MM, default current month
  imbalanceView: 'daily' | 'monthly';

  // Import filters
  importStatusFilter: string | null;
  importZoneFilter: string | null;
  importDateFrom: string | null;
  importDateTo: string | null;

  // Alert filters
  alertStatusFilter: string | null;
  alertSeverityFilter: string[];
  alertCategoryFilter: string | null;
  alertDateFrom: string | null;
  alertDateTo: string | null;

  // Actions
  setDeliveryDay: (day: string) => void;
  setZone: (zone: string) => void;
  setBalancingGroupId: (bgId: string | null) => void;
  setYearMonth: (ym: string) => void;
  setImbalanceView: (view: 'daily' | 'monthly') => void;
  setImportFilters: (filters: Partial<DaImportFilters>) => void;
  setAlertFilters: (filters: Partial<DaAlertFilters>) => void;
  clearAlertFilters: () => void;

  // Derived filter objects for query keys
  getImportFilters: () => DaImportFilters;
  getAlertFilters: () => DaAlertFilters;
}

function todayIso(): string {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function currentYearMonth(): string {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  return `${year}-${month}`;
}

const DEFAULT_ALERT_FILTERS: DaAlertFilters = {
  status: null,
  severity: [],
  category: null,
  dateFrom: null,
  dateTo: null,
};

export const useDaFilters = create<DaFiltersState>((set, get) => ({
  deliveryDay: todayIso(),
  zone: 'DE_LU',
  balancingGroupId: null,
  yearMonth: currentYearMonth(),
  imbalanceView: 'daily',

  importStatusFilter: null,
  importZoneFilter: null,
  importDateFrom: null,
  importDateTo: null,

  alertStatusFilter: null,
  alertSeverityFilter: [],
  alertCategoryFilter: null,
  alertDateFrom: null,
  alertDateTo: null,

  setDeliveryDay: (day) => set({ deliveryDay: day }),

  setZone: (zone) => set({ zone }),

  setBalancingGroupId: (bgId) => set({ balancingGroupId: bgId }),

  setYearMonth: (ym) => set({ yearMonth: ym }),

  setImbalanceView: (view) => set({ imbalanceView: view }),

  setImportFilters: (filters) =>
    set({
      ...(filters.status !== undefined ? { importStatusFilter: filters.status } : {}),
      ...(filters.zone !== undefined ? { importZoneFilter: filters.zone } : {}),
      ...(filters.dateFrom !== undefined ? { importDateFrom: filters.dateFrom } : {}),
      ...(filters.dateTo !== undefined ? { importDateTo: filters.dateTo } : {}),
    }),

  setAlertFilters: (filters) =>
    set({
      ...(filters.status !== undefined ? { alertStatusFilter: filters.status } : {}),
      ...(filters.severity !== undefined ? { alertSeverityFilter: filters.severity } : {}),
      ...(filters.category !== undefined ? { alertCategoryFilter: filters.category } : {}),
      ...(filters.dateFrom !== undefined ? { alertDateFrom: filters.dateFrom } : {}),
      ...(filters.dateTo !== undefined ? { alertDateTo: filters.dateTo } : {}),
    }),

  clearAlertFilters: () =>
    set({
      alertStatusFilter: DEFAULT_ALERT_FILTERS.status,
      alertSeverityFilter: DEFAULT_ALERT_FILTERS.severity,
      alertCategoryFilter: DEFAULT_ALERT_FILTERS.category,
      alertDateFrom: DEFAULT_ALERT_FILTERS.dateFrom,
      alertDateTo: DEFAULT_ALERT_FILTERS.dateTo,
    }),

  getImportFilters: (): DaImportFilters => {
    const state = get();
    return {
      status: state.importStatusFilter,
      zone: state.importZoneFilter,
      dateFrom: state.importDateFrom,
      dateTo: state.importDateTo,
    };
  },

  getAlertFilters: (): DaAlertFilters => {
    const state = get();
    return {
      status: state.alertStatusFilter,
      severity: state.alertSeverityFilter,
      category: state.alertCategoryFilter,
      dateFrom: state.alertDateFrom,
      dateTo: state.alertDateTo,
    };
  },
}));
