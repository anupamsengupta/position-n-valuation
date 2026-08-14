import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { NegativeStyle } from '@/lib/numberUtils';

interface UserPreferencesState {
  timezone: string;
  locale: string;
  negativeNumberStyle: NegativeStyle;
  zebraStriping: boolean;
  theme: 'light' | 'dark' | 'system';
  setPreference: <K extends keyof Omit<UserPreferencesState, 'setPreference'>>(
    key: K,
    value: Omit<UserPreferencesState, 'setPreference'>[K],
  ) => void;
}

/**
 * User preferences store.
 * Persisted to localStorage under a non-tenant-scoped key.
 * These are user preferences, not tenant data (D-14 safe).
 */
export const useUserPreferences = create<UserPreferencesState>()(
  persist(
    (set) => ({
      timezone: 'Europe/Berlin',
      locale: 'de-DE',
      negativeNumberStyle: 'red',
      zebraStriping: true,
      theme: 'light',
      setPreference: (key, value) => set({ [key]: value }),
    }),
    {
      name: 'pv-user-preferences',
    },
  ),
);
