import { describe, it, expect } from 'vitest';
import {
  localDateToUtcBoundary,
  getDstInfo,
  formatIntervalTime,
  getMonthRange,
  getYearRange,
  getYearSpanRange,
  getCurrentQuarterRange,
  adjustRangeForGranularity,
} from './dateUtils';

describe('localDateToUtcBoundary', () => {
  it('converts a CET winter date correctly', () => {
    // Jan 15 in Europe/Berlin (CET, UTC+1) -> midnight is 23:00 UTC on Jan 14
    const result = localDateToUtcBoundary('2026-01-15', 'Europe/Berlin');
    expect(result).toBe('2026-01-14T23:00:00.000Z');
  });

  it('converts a CEST summer date correctly', () => {
    // Aug 1 in Europe/Berlin (CEST, UTC+2) -> midnight is 22:00 UTC on Jul 31
    const result = localDateToUtcBoundary('2026-08-01', 'Europe/Berlin');
    expect(result).toBe('2026-07-31T22:00:00.000Z');
  });
});

describe('getDstInfo', () => {
  it('identifies spring-forward day (92 intervals)', () => {
    const result = getDstInfo(92);
    expect(result.isDstDay).toBe(true);
    expect(result.type).toBe('spring-forward');
    expect(result.label).toContain('23-hour');
  });

  it('identifies fall-back day (100 intervals)', () => {
    const result = getDstInfo(100);
    expect(result.isDstDay).toBe(true);
    expect(result.type).toBe('fall-back');
    expect(result.label).toContain('25-hour');
  });

  it('identifies normal day (96 intervals)', () => {
    const result = getDstInfo(96);
    expect(result.isDstDay).toBe(false);
    expect(result.type).toBe('normal');
  });
});

describe('formatIntervalTime', () => {
  it('formats a winter UTC time to CET local', () => {
    const result = formatIntervalTime('2026-01-15T12:00:00Z', 'Europe/Berlin');
    expect(result.local).toContain('13:00');
    expect(result.utc).toContain('12:00');
    expect(result.utc).toContain('UTC');
  });

  it('formats a summer UTC time to CEST local', () => {
    const result = formatIntervalTime('2026-08-01T12:00:00Z', 'Europe/Berlin');
    expect(result.local).toContain('14:00');
    expect(result.utc).toContain('12:00');
  });
});

describe('getMonthRange', () => {
  it('returns first and last day of a 31-day month', () => {
    expect(getMonthRange(2026, 8)).toEqual({
      rangeStart: '2026-08-01',
      rangeEnd: '2026-08-31',
    });
  });

  it('returns first and last day of February (non-leap)', () => {
    expect(getMonthRange(2026, 2)).toEqual({
      rangeStart: '2026-02-01',
      rangeEnd: '2026-02-28',
    });
  });

  it('returns first and last day of February (leap year)', () => {
    expect(getMonthRange(2028, 2)).toEqual({
      rangeStart: '2028-02-01',
      rangeEnd: '2028-02-29',
    });
  });

  it('handles single-digit months with zero-padding', () => {
    expect(getMonthRange(2026, 1)).toEqual({
      rangeStart: '2026-01-01',
      rangeEnd: '2026-01-31',
    });
  });
});

describe('getYearRange', () => {
  it('returns Jan 1 to Dec 31', () => {
    expect(getYearRange(2026)).toEqual({
      rangeStart: '2026-01-01',
      rangeEnd: '2026-12-31',
    });
  });
});

describe('getYearSpanRange', () => {
  it('returns start of first year to end of last year', () => {
    expect(getYearSpanRange(2024, 2028)).toEqual({
      rangeStart: '2024-01-01',
      rangeEnd: '2028-12-31',
    });
  });
});

describe('getCurrentQuarterRange', () => {
  it('returns a valid quarter range', () => {
    const range = getCurrentQuarterRange();
    expect(range.rangeStart).toMatch(/^\d{4}-\d{2}-01$/);
    expect(range.rangeEnd).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    // Start month should be 01, 04, 07, or 10
    const startMonth = Number(range.rangeStart.split('-')[1]);
    expect([1, 4, 7, 10]).toContain(startMonth);
  });
});

describe('adjustRangeForGranularity', () => {
  const monthRange = { rangeStart: '2026-08-01', rangeEnd: '2026-08-31' };
  const yearRange = { rangeStart: '2026-01-01', rangeEnd: '2026-12-31' };

  it('DAILY snaps to month range', () => {
    const result = adjustRangeForGranularity(yearRange, 'DAILY');
    expect(result).toEqual({ rangeStart: '2026-01-01', rangeEnd: '2026-01-31' });
  });

  it('WEEKLY snaps to month range', () => {
    const result = adjustRangeForGranularity(monthRange, 'WEEKLY');
    expect(result).toEqual({ rangeStart: '2026-08-01', rangeEnd: '2026-08-31' });
  });

  it('MONTHLY snaps to year range', () => {
    const result = adjustRangeForGranularity(monthRange, 'MONTHLY');
    expect(result).toEqual({ rangeStart: '2026-01-01', rangeEnd: '2026-12-31' });
  });

  it('YEARLY snaps to 5-year span', () => {
    const result = adjustRangeForGranularity(monthRange, 'YEARLY');
    expect(result).toEqual({ rangeStart: '2024-01-01', rangeEnd: '2028-12-31' });
  });
});
