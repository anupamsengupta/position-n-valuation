import { describe, it, expect } from 'vitest';
import { localDateToUtcBoundary, getDstInfo, formatIntervalTime } from './dateUtils';

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
