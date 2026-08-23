import { cn } from '@/lib/cn';
import { NumericCell } from './NumericCell';
import { parseNumericValue, type NumericPrecision } from '@/lib/numberUtils';

export interface DeviationThresholds {
  /** Absolute deviation at or below this is neutral. Default 0.1 */
  minor: number;
  /** Absolute deviation at or below this is amber/warning. Above is red/significant. Default 5.0 */
  significant: number;
}

export interface DeviationCellProps {
  value: string | number | null | undefined;
  precision: NumericPrecision;
  thresholds?: DeviationThresholds;
  className?: string;
}

type DeviationSeverity = 'neutral' | 'minor' | 'significant';

const DEFAULT_THRESHOLDS: DeviationThresholds = {
  minor: 0.1,
  significant: 5.0,
};

function classifySeverity(
  absValue: number,
  thresholds: DeviationThresholds,
): DeviationSeverity {
  if (absValue <= thresholds.minor) {
    return 'neutral';
  }
  if (absValue <= thresholds.significant) {
    return 'minor';
  }
  return 'significant';
}

const SEVERITY_LABELS: Record<DeviationSeverity, string> = {
  neutral: 'no deviation',
  minor: 'minor deviation',
  significant: 'significant deviation',
};

const SEVERITY_CLASSES: Record<DeviationSeverity, string> = {
  neutral: '',
  minor: 'text-status-transition',
  significant: 'text-red-600 dark:text-red-400',
};

/**
 * Threshold-colored numeric cell for nomination deviations.
 * Wraps NumericCell with color logic based on absolute deviation magnitude.
 * Zero and values within the minor threshold are neutral.
 * Values within the significant threshold are amber. Above are red.
 */
export function DeviationCell({
  value,
  precision,
  thresholds = DEFAULT_THRESHOLDS,
  className,
}: DeviationCellProps) {
  const parsed = parseNumericValue(value);

  if (parsed === null) {
    return (
      <NumericCell
        value={null}
        precision={precision}
        className={className}
        colorNegative={false}
      />
    );
  }

  const absValue = Math.abs(parsed);
  const severity = classifySeverity(absValue, thresholds);
  const severityLabel = SEVERITY_LABELS[severity];

  return (
    <span
      aria-label={`${parsed} ${severity === 'neutral' ? '' : `${severityLabel}`}`.trim()}
      className={cn(SEVERITY_CLASSES[severity], className)}
    >
      <NumericCell
        value={value}
        precision={precision}
        colorNegative={false}
        showSign={true}
      />
    </span>
  );
}
