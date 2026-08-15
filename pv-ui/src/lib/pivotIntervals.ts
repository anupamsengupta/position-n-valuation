/**
 * Pivot utilities for transforming flat interval arrays into
 * rows-per-measure format (intervals become column headers).
 */

import type { SettlementCellDto, ForwardIntervalDetailDto } from '@/schemas/api';
import type { NumericPrecision } from '@/lib/numberUtils';

// ---------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------

export interface PivotRow {
  measure: string;
  precision: NumericPrecision;
  currency?: string;
  showSign?: boolean;
  /** Dynamic keys: timeLabel -> cell value */
  [timeLabel: string]: string | number | boolean | undefined;
}

export interface PivotResult {
  timeLabels: string[];
  rows: PivotRow[];
}

// ---------------------------------------------------------------------------
// Short time formatter — just HH:mm for column headers
// ---------------------------------------------------------------------------

function formatShortTime(utcInstant: string, timezone: string): string {
  const date = new Date(utcInstant);
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: timezone,
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date);
}

// ---------------------------------------------------------------------------
// Settled intervals pivot
// ---------------------------------------------------------------------------

interface SettledMeasureDef {
  measure: string;
  precision: NumericPrecision;
  field: keyof SettlementCellDto;
  useCurrency?: boolean;
  showSign?: boolean;
}

const SETTLED_MEASURES: SettledMeasureDef[] = [
  { measure: 'MW', precision: 'MW', field: 'volumeMw' },
  { measure: 'MWh', precision: 'MWH', field: 'volumeMwh' },
  { measure: 'Price', precision: 'PRICE', field: 'price' },
  { measure: 'Amount', precision: 'MONETARY', field: 'amount', useCurrency: true },
  { measure: 'Market Price', precision: 'PRICE', field: 'marketPrice' },
  { measure: 'Market Amount', precision: 'MONETARY', field: 'marketAmount', useCurrency: true },
  { measure: 'PnL', precision: 'MONETARY', field: 'pnl', useCurrency: true, showSign: true },
];

/**
 * Pivot settled interval cells: flat array -> rows-per-measure with time columns.
 */
export function pivotSettledIntervals(
  cells: SettlementCellDto[],
  timezone: string,
): PivotResult {
  if (cells.length === 0) return { timeLabels: [], rows: [] };

  // Sort by intervalStart and extract unique time labels
  const sorted = [...cells].sort(
    (a, b) => new Date(a.intervalStart).getTime() - new Date(b.intervalStart).getTime(),
  );

  const timeLabels: string[] = [];
  const seen = new Set<string>();
  for (const cell of sorted) {
    const label = formatShortTime(cell.intervalStart, timezone);
    if (!seen.has(label)) {
      seen.add(label);
      timeLabels.push(label);
    }
  }

  // Determine currency from first cell
  const currency = sorted[0]?.currency;

  // Build one row per measure
  const rows: PivotRow[] = SETTLED_MEASURES.map((def) => {
    const row: PivotRow = {
      measure: def.measure,
      precision: def.precision,
      ...(def.useCurrency && currency ? { currency } : {}),
      ...(def.showSign ? { showSign: true } : {}),
    };
    for (const cell of sorted) {
      const label = formatShortTime(cell.intervalStart, timezone);
      row[label] = cell[def.field] as string | number;
    }
    return row;
  });

  return { timeLabels, rows };
}

// ---------------------------------------------------------------------------
// Forward intervals pivot
// ---------------------------------------------------------------------------

interface ForwardMeasureDef {
  measure: string;
  precision: NumericPrecision;
  field: keyof ForwardIntervalDetailDto;
  useCurrency?: boolean;
}

const FORWARD_MEASURES: ForwardMeasureDef[] = [
  { measure: 'MW', precision: 'MW', field: 'resolvedQty' },
  { measure: 'MWh', precision: 'MWH', field: 'resolvedEnergy' },
  { measure: 'Curve Price', precision: 'PRICE', field: 'evaluatedPrice' },
  { measure: 'Mark Value', precision: 'MONETARY', field: 'markValue', useCurrency: true },
];

/**
 * Pivot forward interval cells: flat array -> rows-per-measure with time columns.
 */
export function pivotForwardIntervals(
  cells: ForwardIntervalDetailDto[],
  timezone: string,
): PivotResult {
  if (cells.length === 0) return { timeLabels: [], rows: [] };

  const sorted = [...cells].sort(
    (a, b) => new Date(a.intervalStart).getTime() - new Date(b.intervalStart).getTime(),
  );

  const timeLabels: string[] = [];
  const seen = new Set<string>();
  for (const cell of sorted) {
    const label = formatShortTime(cell.intervalStart, timezone);
    if (!seen.has(label)) {
      seen.add(label);
      timeLabels.push(label);
    }
  }

  const currency = sorted[0]?.currency ?? undefined;

  const rows: PivotRow[] = FORWARD_MEASURES.map((def) => {
    const row: PivotRow = {
      measure: def.measure,
      precision: def.precision,
      ...(def.useCurrency && currency ? { currency } : {}),
    };
    for (const cell of sorted) {
      const label = formatShortTime(cell.intervalStart, timezone);
      row[label] = cell[def.field] as string | number;
    }
    return row;
  });

  return { timeLabels, rows };
}
