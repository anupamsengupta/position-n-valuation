import { cn } from '@/lib/cn';
import {
  formatNumber,
  isNegativeValue,
  parseNumericValue,
  type NumericPrecision,
  type NegativeStyle,
} from '@/lib/numberUtils';

export interface NumericCellProps {
  value: string | number | null | undefined;
  precision: NumericPrecision;
  currency?: string;
  colorNegative?: boolean;
  showSign?: boolean;
  nullDisplay?: string;
  negativeStyle?: NegativeStyle;
  locale?: string;
  className?: string;
}

/**
 * Right-aligned, monospace-font numeric cell with domain-appropriate formatting.
 * Matches backend NumericPrecision rules exactly.
 */
export function NumericCell({
  value,
  precision,
  currency,
  colorNegative = true,
  showSign = false,
  nullDisplay,
  negativeStyle = 'red',
  locale,
  className,
}: NumericCellProps) {
  const formatted = formatNumber(value, precision, {
    currency,
    locale,
    negativeStyle,
  });
  const isNeg = isNegativeValue(value);
  const parsedValue = parseNumericValue(value);
  const isNull = parsedValue === null;

  // Build aria-label for screen reader
  let ariaLabel: string;
  if (isNull) {
    ariaLabel = 'No data';
  } else {
    const absFormatted = formatNumber(Math.abs(parsedValue), precision, {
      currency,
      locale,
      negativeStyle: 'red', // no parens for aria
    });
    ariaLabel = isNeg ? `Negative ${absFormatted}` : absFormatted;
  }

  // Positive sign prefix
  const displayValue =
    showSign && parsedValue !== null && parsedValue > 0 ? `+${formatted}` : formatted;

  // Use the user's nullDisplay or default em-dash
  const finalDisplay = isNull ? (nullDisplay ?? '\u2014') : displayValue;

  return (
    <span
      className={cn(
        'font-mono tabular-nums text-right whitespace-nowrap',
        isNeg && colorNegative && 'text-numeric-negative',
        isNull && 'text-text-muted',
        className,
      )}
      aria-label={ariaLabel}
    >
      {finalDisplay}
    </span>
  );
}
