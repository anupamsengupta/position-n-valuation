import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';
import { useTenantStore } from './useTenantStore';
import { daKeys } from '@/api/daQueryKeys';
import {
  useDaImportHistory,
  useDaImportDetail,
  useDaSettlement,
  useDaNominations,
  useDaBalancingGroups,
  useDaImbalanceDaily,
  useDaImbalanceMonthly,
  useDaFees,
  useDaKpi,
  useDaAlerts,
  useDaAlertCounts,
} from './useDaQueries';
import type { DaImportFilters, DaAlertFilters } from './useDaFilters';

// Mock all API functions to avoid real network calls
vi.mock('@/api/daApi', () => ({
  fetchDaImportHistory: vi.fn().mockResolvedValue([]),
  fetchDaImportDetail: vi.fn().mockResolvedValue({ status: 'PENDING' }),
  fetchDaSettlement: vi.fn().mockResolvedValue({ rows: [], summary: {} }),
  fetchDaNominations: vi.fn().mockResolvedValue({ rows: [], summary: {} }),
  fetchDaBalancingGroups: vi.fn().mockResolvedValue([]),
  fetchDaImbalanceDaily: vi.fn().mockResolvedValue({ rows: [], summary: {} }),
  fetchDaImbalanceMonthly: vi.fn().mockResolvedValue({ dailySummaries: [] }),
  fetchDaFees: vi.fn().mockResolvedValue({ items: [] }),
  fetchDaKpi: vi.fn().mockResolvedValue({}),
  fetchDaAlerts: vi.fn().mockResolvedValue([]),
  fetchDaAlertCounts: vi.fn().mockResolvedValue({}),
}));

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
      },
    },
  });
  return function Wrapper({ children }: { children: React.ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children);
  };
}

describe('DA Query Hooks', () => {
  beforeEach(() => {
    useTenantStore.getState().setTenant('TN_0042', 'Test Tenant');
  });

  describe('useDaImportHistory', () => {
    it('is enabled when tenantId is set', () => {
      const filters: DaImportFilters = { status: null, zone: null, dateFrom: null, dateTo: null };
      const { result } = renderHook(() => useDaImportHistory(filters), {
        wrapper: createWrapper(),
      });
      // Query should be enabled (not in idle state)
      expect(result.current.isLoading).toBe(true);
    });

    it('is disabled when tenantId is empty', () => {
      useTenantStore.getState().setTenant('', '');
      const filters: DaImportFilters = { status: null, zone: null, dateFrom: null, dateTo: null };
      const { result } = renderHook(() => useDaImportHistory(filters), {
        wrapper: createWrapper(),
      });
      expect(result.current.fetchStatus).toBe('idle');
    });
  });

  describe('useDaImportDetail', () => {
    it('is disabled when sessionId is null', () => {
      const { result } = renderHook(() => useDaImportDetail(null), {
        wrapper: createWrapper(),
      });
      expect(result.current.fetchStatus).toBe('idle');
    });

    it('is enabled when sessionId and tenantId are set', () => {
      const { result } = renderHook(() => useDaImportDetail('sess-001'), {
        wrapper: createWrapper(),
      });
      expect(result.current.isLoading).toBe(true);
    });
  });

  describe('useDaSettlement', () => {
    it('is enabled with required params', () => {
      const { result } = renderHook(() => useDaSettlement('2026-09-16', 'DE_LU'), {
        wrapper: createWrapper(),
      });
      expect(result.current.isLoading).toBe(true);
    });

    it('is disabled when deliveryDay is empty', () => {
      const { result } = renderHook(() => useDaSettlement('', 'DE_LU'), {
        wrapper: createWrapper(),
      });
      expect(result.current.fetchStatus).toBe('idle');
    });
  });

  describe('useDaNominations', () => {
    it('is enabled with required params', () => {
      const { result } = renderHook(() => useDaNominations('2026-09-16', 'DE_LU', null), {
        wrapper: createWrapper(),
      });
      expect(result.current.isLoading).toBe(true);
    });
  });

  describe('useDaBalancingGroups', () => {
    it('is enabled when tenantId is set', () => {
      const { result } = renderHook(() => useDaBalancingGroups(), {
        wrapper: createWrapper(),
      });
      expect(result.current.isLoading).toBe(true);
    });
  });

  describe('useDaImbalanceDaily', () => {
    it('is disabled when bgId is null', () => {
      const { result } = renderHook(() => useDaImbalanceDaily('2026-09-16', null), {
        wrapper: createWrapper(),
      });
      expect(result.current.fetchStatus).toBe('idle');
    });

    it('is enabled when bgId is provided', () => {
      const { result } = renderHook(() => useDaImbalanceDaily('2026-09-16', 'BG-001'), {
        wrapper: createWrapper(),
      });
      expect(result.current.isLoading).toBe(true);
    });
  });

  describe('useDaImbalanceMonthly', () => {
    it('is disabled when bgId is null', () => {
      const { result } = renderHook(() => useDaImbalanceMonthly('2026-09', null), {
        wrapper: createWrapper(),
      });
      expect(result.current.fetchStatus).toBe('idle');
    });
  });

  describe('useDaFees', () => {
    it('is enabled with deliveryDay', () => {
      const { result } = renderHook(() => useDaFees('2026-09-16'), {
        wrapper: createWrapper(),
      });
      expect(result.current.isLoading).toBe(true);
    });
  });

  describe('useDaKpi', () => {
    it('is enabled with deliveryDay and zone', () => {
      const { result } = renderHook(() => useDaKpi('2026-09-16', 'DE_LU'), {
        wrapper: createWrapper(),
      });
      expect(result.current.isLoading).toBe(true);
    });
  });

  describe('useDaAlerts', () => {
    it('is enabled when tenantId is set', () => {
      const filters: DaAlertFilters = { status: null, severity: [], category: null, dateFrom: null, dateTo: null };
      const { result } = renderHook(() => useDaAlerts(filters), {
        wrapper: createWrapper(),
      });
      expect(result.current.isLoading).toBe(true);
    });
  });

  describe('useDaAlertCounts', () => {
    it('is enabled when tenantId is set', () => {
      const { result } = renderHook(() => useDaAlertCounts('OPEN'), {
        wrapper: createWrapper(),
      });
      expect(result.current.isLoading).toBe(true);
    });
  });
});

describe('daKeys factory', () => {
  it('includes tenantId in all keys', () => {
    const tenantId = 'TN_0042';
    const importFilters: DaImportFilters = { status: null, zone: null, dateFrom: null, dateTo: null };
    const alertFilters: DaAlertFilters = { status: null, severity: [], category: null, dateFrom: null, dateTo: null };

    expect(daKeys.importHistory(tenantId, importFilters)).toContain(tenantId);
    expect(daKeys.importDetail(tenantId, 'sess-001')).toContain(tenantId);
    expect(daKeys.settlement(tenantId, '2026-09-16', 'DE_LU', 'Europe/Berlin')).toContain(tenantId);
    expect(daKeys.nominations(tenantId, '2026-09-16', 'DE_LU', null)).toContain(tenantId);
    expect(daKeys.balancingGroups(tenantId)).toContain(tenantId);
    expect(daKeys.imbalanceDaily(tenantId, '2026-09-16', 'BG-001')).toContain(tenantId);
    expect(daKeys.imbalanceMonthly(tenantId, '2026-09', 'BG-001')).toContain(tenantId);
    expect(daKeys.fees(tenantId, '2026-09-16')).toContain(tenantId);
    expect(daKeys.kpi(tenantId, '2026-09-16', 'DE_LU')).toContain(tenantId);
    expect(daKeys.alerts(tenantId, alertFilters)).toContain(tenantId);
    expect(daKeys.alertCounts(tenantId, 'OPEN')).toContain(tenantId);
  });

  it('all keys start with da prefix', () => {
    const tenantId = 'TN_0042';
    expect(daKeys.importDetail(tenantId, 'x')[0]).toBe('da');
    expect(daKeys.settlement(tenantId, 'd', 'z', 't')[0]).toBe('da');
    expect(daKeys.kpi(tenantId, 'd', 'z')[0]).toBe('da');
  });
});
