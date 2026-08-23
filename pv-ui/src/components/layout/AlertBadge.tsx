import { cn } from '@/lib/cn';

export interface AlertBadgeProps {
  count: number;
  maxSeverity: 'CRITICAL' | 'WARNING' | 'INFO' | null;
  collapsed?: boolean;
}

/**
 * Alert count badge for the sidebar bell icon.
 * Expanded mode: colored pill with count.
 * Collapsed mode: small dot indicator.
 * Hidden when count is 0.
 */
export function AlertBadge({ count, maxSeverity, collapsed = false }: AlertBadgeProps) {
  if (count === 0 || maxSeverity === null) {
    return null;
  }

  const colorClass =
    maxSeverity === 'CRITICAL'
      ? 'bg-status-error text-white'
      : maxSeverity === 'WARNING'
        ? 'bg-status-transition text-black'
        : 'bg-text-muted text-white';

  if (collapsed) {
    return (
      <span
        className={cn('absolute top-0.5 right-0.5 w-2 h-2 rounded-full', colorClass)}
        aria-label={`${count} open alert${count === 1 ? '' : 's'}`}
      />
    );
  }

  return (
    <span
      className={cn(
        'inline-flex items-center justify-center min-w-[18px] h-[18px] px-1',
        'rounded-full text-[10px] font-bold leading-none',
        colorClass,
      )}
      aria-label={`${count} open alert${count === 1 ? '' : 's'}`}
    >
      {count > 99 ? '99+' : count}
    </span>
  );
}
