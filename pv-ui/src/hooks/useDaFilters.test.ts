import { describe, it, expect, beforeEach } from 'vitest';
import { useDaFilters } from './useDaFilters';

describe('useDaFilters', () => {
  beforeEach(() => {
    // Reset store to defaults between tests
    const store = useDaFilters.getState();
    store.setDeliveryDay(todayIso());
    store.setZone('DE_LU');
    store.setBalancingGroupId(null);
    store.setImbalanceView('daily');
    store.clearAlertFilters();
    store.setImportFilters({ status: null, zone: null, dateFrom: null, dateTo: null });
  });

  it('has sensible defaults', () => {
    const state = useDaFilters.getState();
    expect(state.deliveryDay).toBe(todayIso());
    expect(state.zone).toBe('DE_LU');
    expect(state.balancingGroupId).toBeNull();
    expect(state.imbalanceView).toBe('daily');
    expect(state.importStatusFilter).toBeNull();
    expect(state.alertStatusFilter).toBeNull();
    expect(state.alertSeverityFilter).toEqual([]);
  });

  it('setDeliveryDay updates state', () => {
    useDaFilters.getState().setDeliveryDay('2026-09-20');
    expect(useDaFilters.getState().deliveryDay).toBe('2026-09-20');
  });

  it('setZone updates state', () => {
    useDaFilters.getState().setZone('FR');
    expect(useDaFilters.getState().zone).toBe('FR');
  });

  it('setBalancingGroupId updates state', () => {
    useDaFilters.getState().setBalancingGroupId('BG-001');
    expect(useDaFilters.getState().balancingGroupId).toBe('BG-001');
    useDaFilters.getState().setBalancingGroupId(null);
    expect(useDaFilters.getState().balancingGroupId).toBeNull();
  });

  it('setImbalanceView updates state', () => {
    useDaFilters.getState().setImbalanceView('monthly');
    expect(useDaFilters.getState().imbalanceView).toBe('monthly');
  });

  it('setImportFilters partially updates import filters', () => {
    useDaFilters.getState().setImportFilters({ status: 'IMPORTED', zone: 'DE_LU' });
    const state = useDaFilters.getState();
    expect(state.importStatusFilter).toBe('IMPORTED');
    expect(state.importZoneFilter).toBe('DE_LU');
    expect(state.importDateFrom).toBeNull();
  });

  it('setAlertFilters partially updates alert filters', () => {
    useDaFilters.getState().setAlertFilters({
      severity: ['CRITICAL', 'WARNING'],
      category: 'AUCTION_INGESTION',
    });
    const state = useDaFilters.getState();
    expect(state.alertSeverityFilter).toEqual(['CRITICAL', 'WARNING']);
    expect(state.alertCategoryFilter).toBe('AUCTION_INGESTION');
    expect(state.alertStatusFilter).toBeNull();
  });

  it('clearAlertFilters resets all alert filters', () => {
    useDaFilters.getState().setAlertFilters({
      status: 'OPEN',
      severity: ['CRITICAL'],
      category: 'SETTLEMENT',
      dateFrom: '2026-09-01',
      dateTo: '2026-09-30',
    });
    useDaFilters.getState().clearAlertFilters();
    const state = useDaFilters.getState();
    expect(state.alertStatusFilter).toBeNull();
    expect(state.alertSeverityFilter).toEqual([]);
    expect(state.alertCategoryFilter).toBeNull();
    expect(state.alertDateFrom).toBeNull();
    expect(state.alertDateTo).toBeNull();
  });

  it('getImportFilters returns structured filter object', () => {
    useDaFilters.getState().setImportFilters({ status: 'PENDING', dateFrom: '2026-09-01' });
    const filters = useDaFilters.getState().getImportFilters();
    expect(filters).toEqual({
      status: 'PENDING',
      zone: null,
      dateFrom: '2026-09-01',
      dateTo: null,
    });
  });

  it('getAlertFilters returns structured filter object', () => {
    useDaFilters.getState().setAlertFilters({ severity: ['WARNING'] });
    const filters = useDaFilters.getState().getAlertFilters();
    expect(filters).toEqual({
      status: null,
      severity: ['WARNING'],
      category: null,
      dateFrom: null,
      dateTo: null,
    });
  });
});

function todayIso(): string {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}
