import type { PeriodStatus } from '@/schemas/types';

/**
 * Derive period status from interval start/end times compared to now.
 * - SETTLED: intervalEnd < now (fully delivered)
 * - FORWARD: intervalStart > now (not yet started)
 * - TRANSITION: intervalStart <= now <= intervalEnd (in delivery)
 */
export function derivePeriodStatus(intervalStart: string, intervalEnd: string): PeriodStatus {
  const now = Date.now();
  const start = new Date(intervalStart).getTime();
  const end = new Date(intervalEnd).getTime();

  if (end <= now) {
    return 'SETTLED';
  }
  if (start > now) {
    return 'FORWARD';
  }
  return 'TRANSITION';
}
