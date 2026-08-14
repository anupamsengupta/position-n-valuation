/**
 * Date and timezone utilities for CET/CEST DST handling.
 *
 * All timestamps from the API are UTC. The UI converts to the display
 * timezone (default Europe/Berlin) using Intl.DateTimeFormat.
 *
 * DST transitions:
 * - Spring-forward (last Sunday of March): 23-hour day, 92 quarter-hour intervals
 * - Fall-back (last Sunday of October): 25-hour day, 100 quarter-hour intervals
 */

/**
 * Convert a local date string (YYYY-MM-DD) in a given timezone to a UTC ISO-8601 instant.
 * This correctly handles DST boundaries:
 * - '2026-08-01' in 'Europe/Berlin' (CEST) -> '2026-07-31T22:00:00.000Z'
 * - '2026-01-15' in 'Europe/Berlin' (CET)  -> '2026-01-14T23:00:00.000Z'
 */
export function localDateToUtcBoundary(localDate: string, timezone: string): string {
  // Create a date at midnight in the specified timezone using Intl
  // Parse year/month/day from the local date string
  const parts = localDate.split('-');
  const year = Number(parts[0]);
  const month = Number(parts[1]) - 1; // 0-indexed
  const day = Number(parts[2]);

  // Create a Date object and use Intl to find the UTC offset at that local time
  // Strategy: iterate to find the UTC instant that corresponds to midnight local time
  // Start with a guess: midnight UTC on the same calendar date
  const guessUtc = new Date(Date.UTC(year, month, day, 0, 0, 0));

  // Format in the target timezone to see what local time our guess corresponds to
  const formatter = new Intl.DateTimeFormat('en-US', {
    timeZone: timezone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });

  const formatted = formatter.format(guessUtc);
  // Parse the formatted string to extract the local hour
  const timePart = formatted.split(', ')[1];
  if (!timePart) {
    // Fallback: use a different approach
    return guessUtc.toISOString();
  }
  const hourMinParts = timePart.split(':');
  const localHour = Number(hourMinParts[0] === '24' ? '0' : hourMinParts[0]);
  const localMin = Number(hourMinParts[1]);

  // The offset from UTC to local is localHour (+ localMin) hours
  // We need to subtract this offset from midnight to get the UTC time of local midnight
  const offsetMinutes = localHour * 60 + localMin;

  // But we also need to account for date differences
  const datePart = formatted.split(', ')[0];
  if (datePart) {
    const dateParts = datePart.split('/');
    const fmtMonth = Number(dateParts[0]) - 1;
    const fmtDay = Number(dateParts[1]);
    const fmtYear = Number(dateParts[2]);

    // Calculate day offset between the formatted date and the target date
    const targetDate = new Date(Date.UTC(year, month, day));
    const formattedDate = new Date(Date.UTC(fmtYear, fmtMonth, fmtDay));
    const dayDiff = (formattedDate.getTime() - targetDate.getTime()) / (24 * 60 * 60 * 1000);

    // Adjust: if the formatted date is ahead of the target, the UTC time is behind
    const totalOffsetMinutes = dayDiff * 24 * 60 + offsetMinutes;
    const utcMidnight = new Date(guessUtc.getTime() - totalOffsetMinutes * 60 * 1000);
    return utcMidnight.toISOString();
  }

  return new Date(guessUtc.getTime() - offsetMinutes * 60 * 1000).toISOString();
}

/**
 * Format a UTC instant as a local date string in the given timezone.
 */
export function formatLocalDate(utcInstant: string, timezone: string): string {
  const date = new Date(utcInstant);
  return new Intl.DateTimeFormat('en-CA', {
    // en-CA gives YYYY-MM-DD format
    timeZone: timezone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(date);
}

/**
 * Format a UTC instant as a local time string with timezone abbreviation.
 * Returns both local and UTC representations for dual-clock display.
 *
 * On DST fall-back days, the timezone abbreviation distinguishes
 * the duplicate hour (02:00 CEST vs 02:00 CET).
 */
export function formatIntervalTime(
  utcInstant: string,
  timezone: string,
): { local: string; utc: string } {
  const date = new Date(utcInstant);

  // Local time with timezone abbreviation
  const localFormatter = new Intl.DateTimeFormat('en-GB', {
    timeZone: timezone,
    hour: '2-digit',
    minute: '2-digit',
    timeZoneName: 'short',
    hour12: false,
  });
  const localFormatted = localFormatter.format(date);

  // UTC time
  const utcFormatter = new Intl.DateTimeFormat('en-GB', {
    timeZone: 'UTC',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
  const utcFormatted = `${utcFormatter.format(date)} UTC`;

  return { local: localFormatted, utc: utcFormatted };
}

/**
 * Format a UTC instant as a period label based on granularity.
 * E.g., for MONTHLY: "Aug 2026", for DAILY: "14 Aug 2026"
 */
export function formatPeriodLabel(
  utcInstant: string,
  granularity: string,
  timezone: string,
): string {
  const date = new Date(utcInstant);

  switch (granularity) {
    case 'YEARLY':
      return new Intl.DateTimeFormat('en-GB', {
        timeZone: timezone,
        year: 'numeric',
      }).format(date);
    case 'MONTHLY':
      return new Intl.DateTimeFormat('en-GB', {
        timeZone: timezone,
        month: 'short',
        year: 'numeric',
      }).format(date);
    case 'WEEKLY':
      return `W${getISOWeek(date, timezone)} ${new Intl.DateTimeFormat('en-GB', {
        timeZone: timezone,
        year: 'numeric',
      }).format(date)}`;
    case 'DAILY':
      return new Intl.DateTimeFormat('en-GB', {
        timeZone: timezone,
        day: '2-digit',
        month: 'short',
        year: 'numeric',
      }).format(date);
    default:
      return new Intl.DateTimeFormat('en-GB', {
        timeZone: timezone,
        day: '2-digit',
        month: 'short',
        year: 'numeric',
      }).format(date);
  }
}

function getISOWeek(date: Date, timezone: string): number {
  // Approximate ISO week from the formatted date
  const formatted = new Intl.DateTimeFormat('en-GB', {
    timeZone: timezone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(date);
  const parts = formatted.split('/');
  const d = new Date(Number(parts[2]), Number(parts[1]) - 1, Number(parts[0]));
  const dayNum = d.getUTCDay() || 7;
  d.setUTCDate(d.getUTCDate() + 4 - dayNum);
  const yearStart = new Date(Date.UTC(d.getUTCFullYear(), 0, 1));
  return Math.ceil(((d.getTime() - yearStart.getTime()) / 86400000 + 1) / 7);
}

/**
 * Get DST info for a day, given its interval count.
 */
export function getDstInfo(intervalCount: number): {
  isDstDay: boolean;
  type: 'spring-forward' | 'fall-back' | 'normal';
  label: string;
} {
  if (intervalCount === 92) {
    return {
      isDstDay: true,
      type: 'spring-forward',
      label: '23-hour delivery day (DST spring-forward). The hour 02:00\u201303:00 CET does not exist.',
    };
  }
  if (intervalCount === 100) {
    return {
      isDstDay: true,
      type: 'fall-back',
      label: '25-hour delivery day (DST fall-back). The hour 02:00\u201303:00 occurs twice.',
    };
  }
  return { isDstDay: false, type: 'normal', label: '' };
}

/**
 * Get default date range: first day of current year to last day of next year.
 */
export function getDefaultDateRange(): { rangeStart: string; rangeEnd: string } {
  const now = new Date();
  const yearStart = `${now.getFullYear()}-01-01`;
  const yearEnd = `${now.getFullYear() + 1}-12-31`;
  return { rangeStart: yearStart, rangeEnd: yearEnd };
}
