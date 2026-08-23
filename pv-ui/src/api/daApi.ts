/**
 * Day-Ahead Exchange Spot API functions -- one per endpoint.
 * Each function calls the typed fetch wrapper and validates the response with Zod.
 */

import { z } from 'zod';
import { apiFetch, apiFetchMutation, apiFetchMultipart } from './client';
import {
  auctionImportSessionSchema,
  daSettlementGridSchema,
  daNominationGridSchema,
  balancingGroupSchema,
  daImbalanceDailySchema,
  daImbalanceMonthlySchema,
  daFeeBreakdownSchema,
  daKpiSchema,
  operationalAlertSchema,
  type AuctionImportSessionDto,
  type DaSettlementGridDto,
  type DaNominationGridDto,
  type BalancingGroupDto,
  type DaImbalanceDailyDto,
  type DaImbalanceMonthlyDto,
  type DaFeeBreakdownDto,
  type DaKpiDto,
  type OperationalAlertDto,
} from '@/schemas/daApi';
import { apiResponseSchema } from '@/schemas/api';
import type { DaImportFilters, DaAlertFilters } from '@/hooks/useDaFilters';

// ---------------------------------------------------------------------------
// Import History
// ---------------------------------------------------------------------------

export async function fetchDaImportHistory(
  tenantId: string,
  filters: DaImportFilters,
): Promise<AuctionImportSessionDto[]> {
  const raw = await apiFetch<unknown>(
    '/api/da/import/history',
    {
      exchange: 'EPEX_SPOT',
      biddingZone: filters.zone ?? undefined,
      status: filters.status ?? undefined,
      dateFrom: filters.dateFrom ?? undefined,
      dateTo: filters.dateTo ?? undefined,
    },
    tenantId,
  );
  const schema = apiResponseSchema(z.array(auctionImportSessionSchema));
  const parsed = schema.parse(raw);
  return parsed.data;
}

// ---------------------------------------------------------------------------
// Import Detail
// ---------------------------------------------------------------------------

export async function fetchDaImportDetail(
  tenantId: string,
  sessionId: string,
): Promise<AuctionImportSessionDto> {
  const raw = await apiFetch<unknown>(
    `/api/da/import/${encodeURIComponent(sessionId)}`,
    {},
    tenantId,
  );
  const schema = apiResponseSchema(auctionImportSessionSchema);
  const parsed = schema.parse(raw);
  return parsed.data;
}

// ---------------------------------------------------------------------------
// Trigger Import (JSON body)
// ---------------------------------------------------------------------------

export async function triggerDaImport(
  tenantId: string,
  body: unknown,
): Promise<AuctionImportSessionDto> {
  const raw = await apiFetchMutation<unknown>(
    '/api/da/import',
    'POST',
    body,
    tenantId,
  );
  const schema = apiResponseSchema(auctionImportSessionSchema);
  const parsed = schema.parse(raw);
  return parsed.data;
}

// ---------------------------------------------------------------------------
// Upload Import File (multipart)
// ---------------------------------------------------------------------------

export async function uploadDaImportFile(
  tenantId: string,
  formData: FormData,
): Promise<AuctionImportSessionDto> {
  const raw = await apiFetchMultipart<unknown>(
    '/api/da/import/file',
    formData,
    tenantId,
  );
  const schema = apiResponseSchema(auctionImportSessionSchema);
  const parsed = schema.parse(raw);
  return parsed.data;
}

// ---------------------------------------------------------------------------
// Settlement Grid
// ---------------------------------------------------------------------------

export async function fetchDaSettlement(
  tenantId: string,
  deliveryDay: string,
  zone: string,
  timezone = 'Europe/Berlin',
): Promise<DaSettlementGridDto> {
  const raw = await apiFetch<unknown>(
    '/api/da/settlement',
    { deliveryDay, zone, timezone },
    tenantId,
  );
  const schema = apiResponseSchema(daSettlementGridSchema);
  const parsed = schema.parse(raw);
  return parsed.data;
}

// ---------------------------------------------------------------------------
// Nominations
// ---------------------------------------------------------------------------

export async function fetchDaNominations(
  tenantId: string,
  deliveryDay: string,
  zone: string,
  bgId: string | null,
): Promise<DaNominationGridDto> {
  const raw = await apiFetch<unknown>(
    '/api/da/nominations',
    {
      deliveryDay,
      zone,
      balancingGroupId: bgId ?? undefined,
    },
    tenantId,
  );
  const schema = apiResponseSchema(daNominationGridSchema);
  const parsed = schema.parse(raw);
  return parsed.data;
}

// ---------------------------------------------------------------------------
// Balancing Groups (reference data)
// ---------------------------------------------------------------------------

export async function fetchDaBalancingGroups(
  tenantId: string,
): Promise<BalancingGroupDto[]> {
  const raw = await apiFetch<unknown>(
    '/api/da/nominations/balancing-groups',
    {},
    tenantId,
  );
  const schema = apiResponseSchema(z.array(balancingGroupSchema));
  const parsed = schema.parse(raw);
  return parsed.data;
}

// ---------------------------------------------------------------------------
// Imbalance Daily
// ---------------------------------------------------------------------------

export async function fetchDaImbalanceDaily(
  tenantId: string,
  deliveryDay: string,
  bgId: string,
): Promise<DaImbalanceDailyDto> {
  const raw = await apiFetch<unknown>(
    '/api/da/imbalance/daily',
    { deliveryDay, balancingGroupId: bgId },
    tenantId,
  );
  const schema = apiResponseSchema(daImbalanceDailySchema);
  const parsed = schema.parse(raw);
  return parsed.data;
}

// ---------------------------------------------------------------------------
// Imbalance Monthly
// ---------------------------------------------------------------------------

export async function fetchDaImbalanceMonthly(
  tenantId: string,
  yearMonth: string,
  bgId: string,
): Promise<DaImbalanceMonthlyDto> {
  const raw = await apiFetch<unknown>(
    '/api/da/imbalance/monthly',
    { yearMonth, balancingGroupId: bgId },
    tenantId,
  );
  const schema = apiResponseSchema(daImbalanceMonthlySchema);
  const parsed = schema.parse(raw);
  return parsed.data;
}

// ---------------------------------------------------------------------------
// Fees
// ---------------------------------------------------------------------------

export async function fetchDaFees(
  tenantId: string,
  deliveryDay: string,
): Promise<DaFeeBreakdownDto> {
  const raw = await apiFetch<unknown>(
    '/api/da/fees',
    { deliveryDay },
    tenantId,
  );
  const schema = apiResponseSchema(daFeeBreakdownSchema);
  const parsed = schema.parse(raw);
  return parsed.data;
}

// ---------------------------------------------------------------------------
// KPI Summary
// ---------------------------------------------------------------------------

export async function fetchDaKpi(
  tenantId: string,
  deliveryDay: string,
  zone: string,
): Promise<DaKpiDto> {
  const raw = await apiFetch<unknown>(
    '/api/da/kpi',
    { deliveryDay, zone },
    tenantId,
  );
  const schema = apiResponseSchema(daKpiSchema);
  const parsed = schema.parse(raw);
  return parsed.data;
}

// ---------------------------------------------------------------------------
// Alerts List
// ---------------------------------------------------------------------------

export async function fetchDaAlerts(
  tenantId: string,
  filters: DaAlertFilters,
): Promise<OperationalAlertDto[]> {
  const raw = await apiFetch<unknown>(
    '/api/da/alerts',
    {
      status: filters.status ?? undefined,
      severity: filters.severity.length > 0 ? filters.severity : undefined,
      category: filters.category ?? undefined,
      dateFrom: filters.dateFrom ?? undefined,
      dateTo: filters.dateTo ?? undefined,
    },
    tenantId,
  );
  const schema = apiResponseSchema(z.array(operationalAlertSchema));
  const parsed = schema.parse(raw);
  return parsed.data;
}

// ---------------------------------------------------------------------------
// Alert Counts (for badge)
// ---------------------------------------------------------------------------

export async function fetchDaAlertCounts(
  tenantId: string,
  status: string | null,
): Promise<Record<string, number>> {
  const raw = await apiFetch<unknown>(
    '/api/da/alerts/counts',
    { status: status ?? undefined },
    tenantId,
  );
  // Alert counts return a map, not wrapped in apiResponseSchema
  const schema = apiResponseSchema(z.record(z.string(), z.number()));
  const parsed = schema.parse(raw);
  return parsed.data;
}

// ---------------------------------------------------------------------------
// Alert Acknowledge
// ---------------------------------------------------------------------------

export async function acknowledgeDaAlert(
  tenantId: string,
  alertId: string,
): Promise<OperationalAlertDto> {
  const raw = await apiFetchMutation<unknown>(
    `/api/da/alerts/${encodeURIComponent(alertId)}/ack`,
    'PUT',
    null,
    tenantId,
  );
  const schema = apiResponseSchema(operationalAlertSchema);
  const parsed = schema.parse(raw);
  return parsed.data;
}

// ---------------------------------------------------------------------------
// Alert Resolve
// ---------------------------------------------------------------------------

export async function resolveDaAlert(
  tenantId: string,
  alertId: string,
): Promise<OperationalAlertDto> {
  const raw = await apiFetchMutation<unknown>(
    `/api/da/alerts/${encodeURIComponent(alertId)}/resolve`,
    'PUT',
    null,
    tenantId,
  );
  const schema = apiResponseSchema(operationalAlertSchema);
  const parsed = schema.parse(raw);
  return parsed.data;
}
