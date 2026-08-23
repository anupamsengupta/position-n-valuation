/**
 * Dashboard API functions -- one per endpoint.
 * Each function calls the typed fetch wrapper and validates the response with Zod.
 */

import { z } from 'zod';
import { apiFetch } from './client';
import {
  portfolioSummarySchema,
  rollupCellSchema,
  positionContributionSchema,
  dailyAggregateSchema,
  settlementCellSchema,
  forwardIntervalDetailSchema,
  apiResponseSchema,
  type PortfolioSummaryDto,
  type RollupCellDto,
  type PositionContributionDto,
  type DailyAggregateDto,
  type SettlementCellDto,
  type ForwardIntervalDetailDto,
} from '@/schemas/api';
import type { SubDailyGranularity, TimeGranularity } from '@/schemas/types';

// ---------------------------------------------------------------------------
// A.1: L1 -- Portfolio Summary
// ---------------------------------------------------------------------------

export async function fetchPortfolioSummary(
  tenantId: string,
  portfolioId: string,
  rangeStart: string,
  rangeEnd: string,
  granularity: TimeGranularity,
): Promise<PortfolioSummaryDto[]> {
  const raw = await apiFetch<unknown>(
    `/api/dashboard/portfolios/${encodeURIComponent(portfolioId)}/summary`,
    { rangeStart, rangeEnd, granularity },
    tenantId,
  );
  const schema = apiResponseSchema(z.array(portfolioSummarySchema));
  const parsed = schema.parse(raw);
  return parsed.data;
}

// ---------------------------------------------------------------------------
// A.2: L2 -- Rollup Grid
// ---------------------------------------------------------------------------

export async function fetchRollupGrid(
  tenantId: string,
  portfolioId: string,
  rangeStart: string,
  rangeEnd: string,
  granularity: TimeGranularity,
): Promise<RollupCellDto[]> {
  const raw = await apiFetch<unknown>(
    `/api/dashboard/portfolios/${encodeURIComponent(portfolioId)}/rollups`,
    { rangeStart, rangeEnd, granularity },
    tenantId,
  );
  const schema = apiResponseSchema(z.array(rollupCellSchema));
  const parsed = schema.parse(raw);
  return parsed.data;
}

// ---------------------------------------------------------------------------
// A.3: L3 -- Position Contributions
// ---------------------------------------------------------------------------

export async function fetchPositionContributions(
  tenantId: string,
  portfolioId: string,
  periodStart: string,
  periodEnd: string,
  offset = 0,
  limit = 50,
): Promise<PositionContributionDto[]> {
  const raw = await apiFetch<unknown>(
    `/api/dashboard/portfolios/${encodeURIComponent(portfolioId)}/positions`,
    { periodStart, periodEnd, offset, limit },
    tenantId,
  );
  const schema = apiResponseSchema(z.array(positionContributionSchema));
  const parsed = schema.parse(raw);
  return parsed.data;
}

// ---------------------------------------------------------------------------
// A.4: L4 -- Daily Aggregates
// ---------------------------------------------------------------------------

export async function fetchDailyAggregates(
  tenantId: string,
  portfolioId: string,
  monthStart: string,
  monthEnd: string,
  positionIds: string[] = [],
  timezone = 'Europe/Berlin',
): Promise<DailyAggregateDto[]> {
  const raw = await apiFetch<unknown>(
    `/api/dashboard/portfolios/${encodeURIComponent(portfolioId)}/daily`,
    { monthStart, monthEnd, positionId: positionIds.length > 0 ? positionIds : undefined, timezone },
    tenantId,
  );
  const schema = apiResponseSchema(z.array(dailyAggregateSchema));
  const parsed = schema.parse(raw);
  return parsed.data;
}

// ---------------------------------------------------------------------------
// A.5: L4 -- Settled Day Detail
// ---------------------------------------------------------------------------

export async function fetchSettledDayDetail(
  tenantId: string,
  portfolioId: string,
  dayStart: string,
  dayEnd: string,
  granularity: SubDailyGranularity,
  positionIds: string[] = [],
): Promise<SettlementCellDto[]> {
  const raw = await apiFetch<unknown>(
    '/api/dashboard/settlements/day',
    { portfolioId, dayStart, dayEnd, granularity, positionId: positionIds.length > 0 ? positionIds : undefined },
    tenantId,
  );
  const schema = apiResponseSchema(z.array(settlementCellSchema));
  const parsed = schema.parse(raw);
  return parsed.data;
}

// ---------------------------------------------------------------------------
// A.6: L4 -- Forward Day Detail
// ---------------------------------------------------------------------------

export async function fetchForwardDayDetail(
  tenantId: string,
  portfolioId: string,
  dayStart: string,
  dayEnd: string,
  granularity: SubDailyGranularity,
  positionIds: string[] = [],
): Promise<ForwardIntervalDetailDto[]> {
  const raw = await apiFetch<unknown>(
    '/api/dashboard/forward/day',
    { portfolioId, dayStart, dayEnd, granularity, positionId: positionIds.length > 0 ? positionIds : undefined },
    tenantId,
  );
  const schema = apiResponseSchema(z.array(forwardIntervalDetailSchema));
  const parsed = schema.parse(raw);
  return parsed.data;
}
