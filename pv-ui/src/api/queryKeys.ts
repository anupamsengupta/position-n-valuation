import type { SubDailyGranularity, TimeGranularity } from '@/schemas/types';

/**
 * Query key factory for dashboard data.
 * Every key includes tenantId to prevent cross-tenant cache leaks.
 */
export const dashboardKeys = {
  all: ['dashboard'] as const,

  portfolios: (tenantId: string) =>
    [...dashboardKeys.all, 'portfolios', tenantId] as const,

  portfolioSummary: (
    tenantId: string,
    portfolioId: string,
    rangeStart: string,
    rangeEnd: string,
    granularity: TimeGranularity,
  ) =>
    [...dashboardKeys.all, 'summary', tenantId, portfolioId, rangeStart, rangeEnd, granularity] as const,

  rollupGrid: (
    tenantId: string,
    portfolioId: string,
    rangeStart: string,
    rangeEnd: string,
    granularity: TimeGranularity,
  ) =>
    [...dashboardKeys.all, 'rollups', tenantId, portfolioId, rangeStart, rangeEnd, granularity] as const,

  positionContributions: (
    tenantId: string,
    portfolioId: string,
    periodStart: string,
    periodEnd: string,
  ) =>
    [...dashboardKeys.all, 'positions', tenantId, portfolioId, periodStart, periodEnd] as const,

  dailyAggregates: (
    tenantId: string,
    portfolioId: string,
    monthStart: string,
    monthEnd: string,
    positionId?: string,
  ) =>
    [...dashboardKeys.all, 'daily', tenantId, portfolioId, monthStart, monthEnd, positionId ?? 'all'] as const,

  settledDay: (
    tenantId: string,
    portfolioId: string,
    dayStart: string,
    dayEnd: string,
    granularity: SubDailyGranularity,
    positionId?: string,
  ) =>
    [...dashboardKeys.all, 'settled-day', tenantId, portfolioId, dayStart, dayEnd, granularity, positionId ?? 'all'] as const,

  forwardDay: (
    tenantId: string,
    portfolioId: string,
    dayStart: string,
    dayEnd: string,
    granularity: SubDailyGranularity,
    positionId?: string,
  ) =>
    [...dashboardKeys.all, 'forward-day', tenantId, portfolioId, dayStart, dayEnd, granularity, positionId ?? 'all'] as const,

  cardSummary: (tenantId: string, portfolioId: string, rangeStart: string, rangeEnd: string) =>
    [...dashboardKeys.all, 'card-summary', tenantId, portfolioId, rangeStart, rangeEnd] as const,
} as const;
