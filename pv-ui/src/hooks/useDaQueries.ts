/**
 * Custom hooks wrapping useQuery for each Day-Ahead endpoint.
 * Each hook uses Zod-validated API functions and includes the
 * staleness/refetch configuration from UI spec S6.1.2.
 */

import { useQuery } from '@tanstack/react-query';
import { daKeys } from '@/api/daQueryKeys';
import {
  fetchDaImportHistory,
  fetchDaImportDetail,
  fetchDaSettlement,
  fetchDaNominations,
  fetchDaBalancingGroups,
  fetchDaImbalanceDaily,
  fetchDaImbalanceMonthly,
  fetchDaFees,
  fetchDaKpi,
  fetchDaAlerts,
  fetchDaAlertCounts,
} from '@/api/daApi';
import { useTenantStore } from './useTenantStore';
import { TERMINAL_IMPORT_STATUSES } from '@/schemas/daApi';
import type { DaImportFilters, DaAlertFilters } from './useDaFilters';

// ---------------------------------------------------------------------------
// Import History
// ---------------------------------------------------------------------------

export function useDaImportHistory(filters: DaImportFilters) {
  const tenantId = useTenantStore((s) => s.tenantId);
  return useQuery({
    queryKey: daKeys.importHistory(tenantId, filters),
    queryFn: () => fetchDaImportHistory(tenantId, filters),
    enabled: !!tenantId,
    staleTime: 30_000,
    gcTime: 300_000,
    refetchInterval: 60_000,
  });
}

// ---------------------------------------------------------------------------
// Import Detail (dynamic polling)
// ---------------------------------------------------------------------------

export function useDaImportDetail(sessionId: string | null) {
  const tenantId = useTenantStore((s) => s.tenantId);
  return useQuery({
    queryKey: daKeys.importDetail(tenantId, sessionId ?? ''),
    queryFn: () => fetchDaImportDetail(tenantId, sessionId!),
    enabled: !!tenantId && !!sessionId,
    staleTime: 0,
    gcTime: 300_000,
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      if (status && TERMINAL_IMPORT_STATUSES.has(status)) return false;
      return 2_000; // 2 seconds while non-terminal
    },
  });
}

// ---------------------------------------------------------------------------
// Settlement Grid
// ---------------------------------------------------------------------------

export function useDaSettlement(
  deliveryDay: string,
  zone: string,
  timezone = 'Europe/Berlin',
) {
  const tenantId = useTenantStore((s) => s.tenantId);
  return useQuery({
    queryKey: daKeys.settlement(tenantId, deliveryDay, zone, timezone),
    queryFn: () => fetchDaSettlement(tenantId, deliveryDay, zone, timezone),
    enabled: !!tenantId && !!deliveryDay && !!zone,
    staleTime: 120_000,
    gcTime: 300_000,
    // Settled data is stable; refetch via SSE invalidation
  });
}

// ---------------------------------------------------------------------------
// Nominations
// ---------------------------------------------------------------------------

export function useDaNominations(
  deliveryDay: string,
  zone: string,
  bgId: string | null,
) {
  const tenantId = useTenantStore((s) => s.tenantId);
  return useQuery({
    queryKey: daKeys.nominations(tenantId, deliveryDay, zone, bgId),
    queryFn: () => fetchDaNominations(tenantId, deliveryDay, zone, bgId),
    enabled: !!tenantId && !!deliveryDay && !!zone,
    staleTime: 60_000,
    gcTime: 300_000,
    refetchInterval: 120_000,
  });
}

// ---------------------------------------------------------------------------
// Balancing Groups (reference data)
// ---------------------------------------------------------------------------

export function useDaBalancingGroups() {
  const tenantId = useTenantStore((s) => s.tenantId);
  return useQuery({
    queryKey: daKeys.balancingGroups(tenantId),
    queryFn: () => fetchDaBalancingGroups(tenantId),
    enabled: !!tenantId,
    staleTime: 300_000,
    gcTime: 600_000,
    // Reference data; no polling needed
  });
}

// ---------------------------------------------------------------------------
// Imbalance Daily
// ---------------------------------------------------------------------------

export function useDaImbalanceDaily(deliveryDay: string, bgId: string | null) {
  const tenantId = useTenantStore((s) => s.tenantId);
  return useQuery({
    queryKey: daKeys.imbalanceDaily(tenantId, deliveryDay, bgId ?? ''),
    queryFn: () => fetchDaImbalanceDaily(tenantId, deliveryDay, bgId!),
    enabled: !!tenantId && !!deliveryDay && !!bgId,
    staleTime: 120_000,
    gcTime: 300_000,
    // Stable after TSO publication; no polling
  });
}

// ---------------------------------------------------------------------------
// Imbalance Monthly
// ---------------------------------------------------------------------------

export function useDaImbalanceMonthly(yearMonth: string, bgId: string | null) {
  const tenantId = useTenantStore((s) => s.tenantId);
  return useQuery({
    queryKey: daKeys.imbalanceMonthly(tenantId, yearMonth, bgId ?? ''),
    queryFn: () => fetchDaImbalanceMonthly(tenantId, yearMonth, bgId!),
    enabled: !!tenantId && !!yearMonth && !!bgId,
    staleTime: 120_000,
    gcTime: 300_000,
    // Aggregated view; no polling
  });
}

// ---------------------------------------------------------------------------
// Fees
// ---------------------------------------------------------------------------

export function useDaFees(deliveryDay: string) {
  const tenantId = useTenantStore((s) => s.tenantId);
  return useQuery({
    queryKey: daKeys.fees(tenantId, deliveryDay),
    queryFn: () => fetchDaFees(tenantId, deliveryDay),
    enabled: !!tenantId && !!deliveryDay,
    staleTime: 300_000,
    gcTime: 300_000,
    // Stable once computed; no polling
  });
}

// ---------------------------------------------------------------------------
// KPI Summary
// ---------------------------------------------------------------------------

export function useDaKpi(deliveryDay: string, zone: string) {
  const tenantId = useTenantStore((s) => s.tenantId);
  return useQuery({
    queryKey: daKeys.kpi(tenantId, deliveryDay, zone),
    queryFn: () => fetchDaKpi(tenantId, deliveryDay, zone),
    enabled: !!tenantId && !!deliveryDay && !!zone,
    staleTime: 30_000,
    gcTime: 300_000,
    refetchInterval: 60_000,
  });
}

// ---------------------------------------------------------------------------
// Alerts List
// ---------------------------------------------------------------------------

export function useDaAlerts(filters: DaAlertFilters) {
  const tenantId = useTenantStore((s) => s.tenantId);
  return useQuery({
    queryKey: daKeys.alerts(tenantId, filters),
    queryFn: () => fetchDaAlerts(tenantId, filters),
    enabled: !!tenantId,
    staleTime: 10_000,
    gcTime: 300_000,
    refetchInterval: 30_000,
  });
}

// ---------------------------------------------------------------------------
// Alert Counts (for badge)
// ---------------------------------------------------------------------------

export function useDaAlertCounts(status: string | null = 'OPEN') {
  const tenantId = useTenantStore((s) => s.tenantId);
  return useQuery({
    queryKey: daKeys.alertCounts(tenantId, status),
    queryFn: () => fetchDaAlertCounts(tenantId, status),
    enabled: !!tenantId,
    staleTime: 10_000,
    gcTime: 300_000,
    refetchInterval: 30_000,
  });
}
