/**
 * Custom hooks wrapping useQuery for each dashboard endpoint.
 * Each hook uses Zod-validated API functions and includes the
 * staleness/refetch configuration from spec S6.1.
 */

import { useQuery } from '@tanstack/react-query';
import { dashboardKeys } from '@/api/queryKeys';
import {
  fetchPortfolioSummary,
  fetchRollupGrid,
  fetchPositionContributions,
  fetchDailyAggregates,
  fetchSettledDayDetail,
  fetchForwardDayDetail,
} from '@/api/dashboard';
import { useTenantStore } from './useTenantStore';
import type { SubDailyGranularity, TimeGranularity } from '@/schemas/types';

// ---------------------------------------------------------------------------
// L1: Portfolio Summary
// ---------------------------------------------------------------------------

export function usePortfolioSummary(
  portfolioId: string,
  rangeStart: string,
  rangeEnd: string,
  granularity: TimeGranularity,
) {
  const tenantId = useTenantStore((s) => s.tenantId);
  return useQuery({
    queryKey: dashboardKeys.portfolioSummary(tenantId, portfolioId, rangeStart, rangeEnd, granularity),
    queryFn: () => fetchPortfolioSummary(tenantId, portfolioId, rangeStart, rangeEnd, granularity),
    enabled: !!tenantId && !!portfolioId,
    staleTime: 30_000,
    gcTime: 300_000,
    refetchInterval: 30_000,
  });
}

// ---------------------------------------------------------------------------
// L2: Rollup Grid
// ---------------------------------------------------------------------------

export function useRollupGrid(
  portfolioId: string,
  rangeStart: string,
  rangeEnd: string,
  granularity: TimeGranularity,
) {
  const tenantId = useTenantStore((s) => s.tenantId);
  return useQuery({
    queryKey: dashboardKeys.rollupGrid(tenantId, portfolioId, rangeStart, rangeEnd, granularity),
    queryFn: () => fetchRollupGrid(tenantId, portfolioId, rangeStart, rangeEnd, granularity),
    enabled: !!tenantId && !!portfolioId,
    staleTime: 30_000,
    gcTime: 300_000,
    refetchInterval: 30_000,
  });
}

// ---------------------------------------------------------------------------
// L3: Position Contributions
// ---------------------------------------------------------------------------

export function usePositionContributions(
  portfolioId: string,
  periodStart: string | undefined,
  periodEnd: string | undefined,
) {
  const tenantId = useTenantStore((s) => s.tenantId);
  return useQuery({
    queryKey: dashboardKeys.positionContributions(
      tenantId,
      portfolioId,
      periodStart ?? '',
      periodEnd ?? '',
    ),
    queryFn: () =>
      fetchPositionContributions(tenantId, portfolioId, periodStart!, periodEnd!),
    enabled: !!tenantId && !!portfolioId && !!periodStart && !!periodEnd,
    staleTime: 60_000,
    gcTime: 300_000,
    refetchInterval: 60_000,
  });
}

// ---------------------------------------------------------------------------
// L4: Daily Aggregates
// ---------------------------------------------------------------------------

export function useDailyAggregates(
  portfolioId: string,
  monthStart: string | undefined,
  monthEnd: string | undefined,
  positionId?: string,
) {
  const tenantId = useTenantStore((s) => s.tenantId);
  return useQuery({
    queryKey: dashboardKeys.dailyAggregates(
      tenantId,
      portfolioId,
      monthStart ?? '',
      monthEnd ?? '',
      positionId,
    ),
    queryFn: () =>
      fetchDailyAggregates(tenantId, portfolioId, monthStart!, monthEnd!, positionId),
    enabled: !!tenantId && !!portfolioId && !!monthStart && !!monthEnd,
    staleTime: 30_000,
    gcTime: 300_000,
    refetchInterval: 30_000,
  });
}

// ---------------------------------------------------------------------------
// L4: Settled Day Detail
// ---------------------------------------------------------------------------

export function useSettledDayDetail(
  portfolioId: string,
  dayStart: string | undefined,
  dayEnd: string | undefined,
  granularity: SubDailyGranularity,
  positionId?: string,
) {
  const tenantId = useTenantStore((s) => s.tenantId);
  return useQuery({
    queryKey: dashboardKeys.settledDay(
      tenantId,
      portfolioId,
      dayStart ?? '',
      dayEnd ?? '',
      granularity,
      positionId,
    ),
    queryFn: () =>
      fetchSettledDayDetail(tenantId, portfolioId, dayStart!, dayEnd!, granularity, positionId),
    enabled: !!tenantId && !!portfolioId && !!dayStart && !!dayEnd,
    staleTime: 120_000,
    gcTime: 300_000,
    // Settled data is stable; no refetchInterval per spec S6.1
  });
}

// ---------------------------------------------------------------------------
// L4: Forward Day Detail
// ---------------------------------------------------------------------------

export function useForwardDayDetail(
  portfolioId: string,
  dayStart: string | undefined,
  dayEnd: string | undefined,
  granularity: SubDailyGranularity,
  positionId?: string,
) {
  const tenantId = useTenantStore((s) => s.tenantId);
  return useQuery({
    queryKey: dashboardKeys.forwardDay(
      tenantId,
      portfolioId,
      dayStart ?? '',
      dayEnd ?? '',
      granularity,
      positionId,
    ),
    queryFn: () =>
      fetchForwardDayDetail(tenantId, portfolioId, dayStart!, dayEnd!, granularity, positionId),
    enabled: !!tenantId && !!portfolioId && !!dayStart && !!dayEnd,
    staleTime: 15_000,
    gcTime: 300_000,
    refetchInterval: 15_000,
  });
}
