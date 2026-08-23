import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { NegativeStyle } from '@/lib/numberUtils';

type PreferenceFields = Omit<
  UserPreferencesState,
  'setPreference' | 'toggleSidebarCollapsed' | 'toggleSectionExpanded'
>;

interface UserPreferencesState {
  timezone: string;
  locale: string;
  negativeNumberStyle: NegativeStyle;
  zebraStriping: boolean;
  theme: 'light' | 'dark' | 'system';
  sidebarCollapsed: boolean;
  sidebarExpanded: Record<string, boolean>;
  setPreference: <K extends keyof PreferenceFields>(
    key: K,
    value: PreferenceFields[K],
  ) => void;
  toggleSidebarCollapsed: () => void;
  toggleSectionExpanded: (sectionKey: string) => void;
}

/**
 * User preferences store.
 * Persisted to localStorage under a non-tenant-scoped key.
 * These are user preferences, not tenant data (D-14 safe).
 */
export const useUserPreferences = create<UserPreferencesState>()(
  persist(
    (set, get) => ({
      timezone: 'Europe/Berlin',
      locale: 'de-DE',
      negativeNumberStyle: 'red',
      zebraStriping: true,
      theme: 'light',
      sidebarCollapsed: false,
      sidebarExpanded: {},
      setPreference: (key, value) => set({ [key]: value }),
      toggleSidebarCollapsed: () => set({ sidebarCollapsed: !get().sidebarCollapsed }),
      toggleSectionExpanded: (sectionKey: string) => {
        const current = get().sidebarExpanded;
        const isExpanded = current[sectionKey] !== false;
        set({
          sidebarExpanded: { ...current, [sectionKey]: !isExpanded },
        });
      },
    }),
    {
      name: 'pv-user-preferences',
    },
  ),
);
