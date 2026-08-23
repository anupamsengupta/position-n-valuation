/**
 * Query key factory for Day-Ahead Exchange Spot data.
 * Every key includes tenantId to prevent cross-tenant cache leaks.
 * Filter objects are serialized into the key so different filter
 * combinations have distinct cache entries.
 */

import type { DaImportFilters, DaAlertFilters } from '@/hooks/useDaFilters';

export const daKeys = {
  all: ['da'] as const,

  importHistory: (tenantId: string, filters: DaImportFilters) =>
    [...daKeys.all, 'import-history', tenantId, filters] as const,

  importDetail: (tenantId: string, sessionId: string) =>
    [...daKeys.all, 'import-detail', tenantId, sessionId] as const,

  settlement: (tenantId: string, deliveryDay: string, zone: string, timezone: string) =>
    [...daKeys.all, 'settlement', tenantId, deliveryDay, zone, timezone] as const,

  nominations: (tenantId: string, deliveryDay: string, zone: string, bgId: string | null) =>
    [...daKeys.all, 'nominations', tenantId, deliveryDay, zone, bgId] as const,

  balancingGroups: (tenantId: string) =>
    [...daKeys.all, 'balancing-groups', tenantId] as const,

  imbalanceDaily: (tenantId: string, deliveryDay: string, bgId: string) =>
    [...daKeys.all, 'imbalance-daily', tenantId, deliveryDay, bgId] as const,

  imbalanceMonthly: (tenantId: string, yearMonth: string, bgId: string) =>
    [...daKeys.all, 'imbalance-monthly', tenantId, yearMonth, bgId] as const,

  fees: (tenantId: string, deliveryDay: string) =>
    [...daKeys.all, 'fees', tenantId, deliveryDay] as const,

  kpi: (tenantId: string, deliveryDay: string, zone: string) =>
    [...daKeys.all, 'kpi', tenantId, deliveryDay, zone] as const,

  alerts: (tenantId: string, filters: DaAlertFilters) =>
    [...daKeys.all, 'alerts', tenantId, filters] as const,

  alertCounts: (tenantId: string, status: string | null) =>
    [...daKeys.all, 'alert-counts', tenantId, status] as const,
} as const;
