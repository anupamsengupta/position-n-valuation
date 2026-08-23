import { create } from 'zustand';

interface AsOfClockState {
  knowledgeTime: string | null;
  businessTime: string | null;
  isNonCurrent: boolean;
  setKnowledgeTime: (t: string | null) => void;
  setBusinessTime: (t: string | null) => void;
  resetToNow: () => void;
}

/**
 * As-of toggle state for bitemporal views.
 *
 * For v1.0, the backend dashboard endpoints do not accept as-of parameters.
 * The store is built and the UI controls are present but disabled.
 * When backend support lands, enabling the toggle requires:
 * 1. Backend endpoints accept knowledgeTime and businessTime parameters
 * 2. Remove the disabled prop from the As-Of Toggle component
 * 3. The query keys already include the as-of dimensions via this store
 */
export const useAsOfClock = create<AsOfClockState>((set) => ({
  knowledgeTime: null,
  businessTime: null,
  isNonCurrent: false,
  setKnowledgeTime: (t) =>
    set({ knowledgeTime: t, isNonCurrent: t !== null }),
  setBusinessTime: (t) =>
    set((state) => ({
      businessTime: t,
      isNonCurrent: t !== null || state.knowledgeTime !== null,
    })),
  resetToNow: () =>
    set({ knowledgeTime: null, businessTime: null, isNonCurrent: false }),
}));
