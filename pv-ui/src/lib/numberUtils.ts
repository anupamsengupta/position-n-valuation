/**
 * Number formatting utilities matching backend NumericPrecision.
 *
 * Precision mapping:
 * - PRICE:       min 2, max 8 decimals
 * - MONETARY:    min 2, max 4 decimals
 * - MW:          min 2, max 4 decimals, suffix "MW"
 * - MWH:         min 2, max 4 decimals, suffix "MWh"
 * - INTERMEDIATE: min 2, max 10 decimals
 * - PERCENTAGE:  min 2, max 2 decimals, suffix "%"
 */

export type NumericPrecision = 'PRICE' | 'MONETARY' | 'MW' | 'MWH' | 'INTERMEDIATE' | 'PERCENTAGE';
export type NegativeStyle = 'red' | 'parentheses' | 'both';

interface PrecisionConfig {
  minimumFractionDigits: number;
  maximumFractionDigits: number;
  suffix: string;
}

const PRECISION_MAP: Record<NumericPrecision, PrecisionConfig> = {
  PRICE: { minimumFractionDigits: 2, maximumFractionDigits: 8, suffix: '' },
  MONETARY: { minimumFractionDigits: 2, maximumFractionDigits: 4, suffix: '' },
  MW: { minimumFractionDigits: 2, maximumFractionDigits: 4, suffix: ' MW' },
  MWH: { minimumFractionDigits: 2, maximumFractionDigits: 4, suffix: ' MWh' },
  INTERMEDIATE: { minimumFractionDigits: 2, maximumFractionDigits: 10, suffix: '' },
  PERCENTAGE: { minimumFractionDigits: 2, maximumFractionDigits: 2, suffix: '%' },
};

export interface FormatNumberOptions {
  currency?: string;
  locale?: string;
  negativeStyle?: NegativeStyle;
}

/**
 * Parses a BigDecimal string or number to a number.
 * Returns null if the input is null, undefined, or not parseable.
 * Logs a warning if the value exceeds Number.MAX_SAFE_INTEGER.
 */
export function parseNumericValue(value: string | number | null | undefined): number | null {
  if (value === null || value === undefined) {
    return null;
  }
  const num = typeof value === 'number' ? value : Number(value);
  if (isNaN(num)) {
    return null;
  }
  if (Math.abs(num) > Number.MAX_SAFE_INTEGER) {
    console.warn(
      `[numberUtils] Value ${String(value)} exceeds Number.MAX_SAFE_INTEGER. ` +
      'Precision may be lost. Consider using Decimal.js for this value.'
    );
  }
  return num;
}

/**
 * Cached Intl.NumberFormat instances keyed by (locale, minFrac, maxFrac).
 * Avoids recreating formatters on every cell render (864+ calls per grid render).
 */
const formatterCache = new Map<string, Intl.NumberFormat>();

function getNumberFormatter(locale: string, minFrac: number, maxFrac: number): Intl.NumberFormat {
  const key = `${locale}:${minFrac}:${maxFrac}`;
  let fmt = formatterCache.get(key);
  if (!fmt) {
    fmt = new Intl.NumberFormat(locale, {
      minimumFractionDigits: minFrac,
      maximumFractionDigits: maxFrac,
    });
    formatterCache.set(key, fmt);
  }
  return fmt;
}

/**
 * Format a numeric value for display, matching backend NumericPrecision rules.
 * Never rounds or truncates below the backend's scale.
 */
export function formatNumber(
  value: string | number | null | undefined,
  precision: NumericPrecision,
  options?: FormatNumberOptions,
): string {
  const num = parseNumericValue(value);
  if (num === null) {
    return '\u2014'; // em-dash for null
  }

  const config = PRECISION_MAP[precision];
  const locale = options?.locale ?? 'de-DE';

  const formatter = getNumberFormatter(
    locale,
    config.minimumFractionDigits,
    config.maximumFractionDigits,
  );

  const absNum = Math.abs(num);
  const formatted = formatter.format(absNum);
  const isNegative = num < 0;

  const suffix = config.suffix
    ? config.suffix
    : options?.currency
      ? ` ${options.currency}`
      : '';

  const negativeStyle = options?.negativeStyle ?? 'red';
  if (isNegative) {
    if (negativeStyle === 'parentheses' || negativeStyle === 'both') {
      return `(${formatted}${suffix})`;
    }
    return `-${formatted}${suffix}`;
  }

  return `${formatted}${suffix}`;
}

/**
 * Returns whether a numeric value is negative.
 * Used by components to apply CSS classes for negative styling.
 */
export function isNegativeValue(value: string | number | null | undefined): boolean {
  const num = parseNumericValue(value);
  return num !== null && num < 0;
}
