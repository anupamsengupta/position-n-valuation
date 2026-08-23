import { create } from 'zustand';

interface DaSelectionState {
  // Import page
  selectedSessionId: string | null;
  setSelectedSession: (id: string | null) => void;

  // Alerts page
  selectedAlertId: string | null;
  expandedAlertId: string | null;
  setSelectedAlert: (id: string | null) => void;
  setExpandedAlert: (id: string | null) => void;

  clearAll: () => void;
}

/**
 * DA selection state for detail panel visibility.
 * Simpler than `useDashboardSelection` because DA pages have flat
 * selection (no multi-level drill-down cascade).
 */
export const useDaSelection = create<DaSelectionState>((set) => ({
  selectedSessionId: null,
  selectedAlertId: null,
  expandedAlertId: null,

  setSelectedSession: (id) => set({ selectedSessionId: id }),

  setSelectedAlert: (id) => set({ selectedAlertId: id }),

  setExpandedAlert: (id) => set({ expandedAlertId: id }),

  clearAll: () =>
    set({
      selectedSessionId: null,
      selectedAlertId: null,
      expandedAlertId: null,
    }),
}));
