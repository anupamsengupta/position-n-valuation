import { describe, it, expect, vi, afterEach } from 'vitest';
import { derivePeriodStatus } from './statusUtils';

describe('derivePeriodStatus', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('returns SETTLED when intervalEnd is in the past', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-14T12:00:00Z'));
    const result = derivePeriodStatus('2026-07-01T00:00:00Z', '2026-07-31T23:59:59Z');
    expect(result).toBe('SETTLED');
  });

  it('returns FORWARD when intervalStart is in the future', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-14T12:00:00Z'));
    const result = derivePeriodStatus('2026-09-01T00:00:00Z', '2026-09-30T23:59:59Z');
    expect(result).toBe('FORWARD');
  });

  it('returns TRANSITION when now is between start and end', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-14T12:00:00Z'));
    const result = derivePeriodStatus('2026-08-01T00:00:00Z', '2026-08-31T23:59:59Z');
    expect(result).toBe('TRANSITION');
  });
});
