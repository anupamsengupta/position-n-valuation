import { NumericCell, type NumericCellProps } from './NumericCell';
import { cn } from '@/lib/cn';

export interface KpiTileProps {
  label: string;
  value: string | number | null | undefined;
  precision: NumericCellProps['precision'];
  currency?: string;
  secondaryLabel?: string;
  secondaryValue?: string | number | null | undefined;
  trend?: 'up' | 'down' | 'flat' | null;
  className?: string;
}

const TREND_ICONS: Record<string, { icon: string; ariaLabel: string; colorClass: string }> = {
  up: { icon: '\u25B2', ariaLabel: 'Trend: up', colorClass: 'text-numeric-positive' },
  down: { icon: '\u25BC', ariaLabel: 'Trend: down', colorClass: 'text-numeric-negative' },
  flat: { icon: '\u25B6', ariaLabel: 'Trend: flat', colorClass: 'text-text-muted' },
};

/**
 * KPI display tile for portfolio summary cards.
 * Label top, large value center, optional secondary value with trend arrow.
 */
export function KpiTile({
  label,
  value,
  precision,
  currency,
  secondaryLabel,
  secondaryValue,
  trend,
  className,
}: KpiTileProps) {
  const trendConfig = trend ? TREND_ICONS[trend] : null;

  return (
    <div className={cn('flex flex-col gap-0.5', className)}>
      <span className="text-sm text-text-secondary">{label}</span>
      <NumericCell
        value={value}
        precision={precision}
        currency={currency}
        className="text-xl font-semibold text-text-primary text-left"
      />
      {(secondaryLabel || secondaryValue !== undefined) && (
        <div className="flex items-center gap-1 text-xs text-text-muted">
          {trendConfig && (
            <span className={trendConfig.colorClass} role="img" aria-label={trendConfig.ariaLabel}>
              {trendConfig.icon}
            </span>
          )}
          {secondaryLabel && <span>{secondaryLabel}</span>}
          {secondaryValue !== undefined && (
            <NumericCell
              value={secondaryValue}
              precision={precision}
              currency={currency}
              className="text-xs text-left"
            />
          )}
        </div>
      )}
    </div>
  );
}
