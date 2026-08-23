import { cn } from '@/lib/cn';

export interface StatusBadgeProps {
  status: 'SETTLED' | 'TRANSITION' | 'PARTIAL' | 'FORWARD' | 'TODAY';
  size?: 'sm' | 'md';
}

const STATUS_CONFIG: Record<
  StatusBadgeProps['status'],
  { label: string; bgClass: string; textClass: string; icon: string }
> = {
  SETTLED: { label: 'Settled', bgClass: 'bg-status-settled', textClass: 'text-white', icon: '\u2713' },
  TRANSITION: { label: 'Transition', bgClass: 'bg-status-transition', textClass: 'text-white dark:text-[#1a1a2e]', icon: '\u25F7' },
  PARTIAL: { label: 'Partial', bgClass: 'bg-status-transition', textClass: 'text-white dark:text-[#1a1a2e]', icon: '\u25F7' },
  TODAY: { label: 'Today', bgClass: 'bg-status-today', textClass: 'text-white dark:text-[#1a1a2e]', icon: '\u25F7' },
  FORWARD: { label: 'Forward', bgClass: 'bg-status-forward', textClass: 'text-white', icon: '\u2192' },
};

/**
 * Pill-shaped status badge with icon and color-coded background.
 * Text colors are theme-aware to maintain WCAG 4.5:1 contrast ratio.
 */
export function StatusBadge({ status, size = 'sm' }: StatusBadgeProps) {
  const config = STATUS_CONFIG[status];

  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-full font-medium',
        config.bgClass,
        config.textClass,
        size === 'sm' ? 'px-1.5 py-0.5 text-xs' : 'px-2 py-1 text-sm',
      )}
      aria-label={`Delivery status: ${config.label}`}
    >
      <span aria-hidden="true">{config.icon}</span>
      {config.label}
    </span>
  );
}
