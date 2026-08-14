import { create } from 'zustand';

interface TenantState {
  tenantId: string;
  tenantName: string;
  setTenant: (id: string, name: string) => void;
}

/**
 * Tenant identity store.
 *
 * Populated from auth context (JWT claim) or the simulator's
 * VITE_DEFAULT_TENANT_ID env var via the app shell on mount.
 * Never defaults to a hardcoded value in this store.
 */
export const useTenantStore = create<TenantState>((set) => ({
  tenantId: '',
  tenantName: '',
  setTenant: (id, name) => set({ tenantId: id, tenantName: name }),
}));
