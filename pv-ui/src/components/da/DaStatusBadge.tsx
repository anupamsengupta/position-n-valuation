import { cn } from '@/lib/cn';

export type DaStatus =
  | 'IMPORTED' | 'IMPORTING' | 'VALIDATING' | 'PENDING'
  | 'VALIDATED'
  | 'VALIDATION_FAILED' | 'IMPORT_FAILED'
  | 'BUY' | 'SELL'
  | 'CRITICAL' | 'WARNING' | 'INFO'
  | 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED'
  | 'OK' | 'DEVIATION' | 'MISSING';

export interface DaStatusBadgeProps {
  status: DaStatus;
  size?: 'sm' | 'md';
  className?: string;
}

interface StatusConfig {
  label: string;
  bgClass: string;
  textClass: string;
  icon: string | null;
  /** Whether the icon should use CSS animate-spin (for spinner) */
  spin: boolean;
}

const STATUS_CONFIG: Record<DaStatus, StatusConfig> = {
  IMPORTED:          { label: 'Imported',   bgClass: 'bg-status-settled',                     textClass: 'text-white',                                    icon: '\u2713', spin: false },
  IMPORTING:         { label: 'Importing',  bgClass: 'bg-status-forward',                     textClass: 'text-white',                                    icon: '\u25E0', spin: true },
  VALIDATING:        { label: 'Validating', bgClass: 'bg-status-forward',                     textClass: 'text-white',                                    icon: '\u25E0', spin: true },
  VALIDATED:         { label: 'Validated',  bgClass: 'bg-status-settled',                     textClass: 'text-white',                                    icon: '\u2713', spin: false },
  PENDING:           { label: 'Pending',    bgClass: 'bg-bg-tertiary',                        textClass: 'text-text-secondary',                           icon: '\u25F7', spin: false },
  VALIDATION_FAILED: { label: 'Failed',     bgClass: 'bg-red-600 dark:bg-red-500',            textClass: 'text-white',                                    icon: '\u2715', spin: false },
  IMPORT_FAILED:     { label: 'Failed',     bgClass: 'bg-red-600 dark:bg-red-500',            textClass: 'text-white',                                    icon: '\u2715', spin: false },
  BUY:               { label: 'BUY',        bgClass: 'bg-status-settled',                     textClass: 'text-white',                                    icon: null,     spin: false },
  SELL:              { label: 'SELL',        bgClass: 'bg-red-600 dark:bg-red-500',            textClass: 'text-white',                                    icon: null,     spin: false },
  CRITICAL:          { label: 'CRIT',       bgClass: 'bg-red-600 dark:bg-red-500',            textClass: 'text-white',                                    icon: null,     spin: false },
  WARNING:           { label: 'WARN',       bgClass: 'bg-status-transition',                  textClass: 'text-white dark:text-[#1a1a2e]',                icon: null,     spin: false },
  INFO:              { label: 'INFO',       bgClass: 'bg-status-forward',                     textClass: 'text-white',                                    icon: null,     spin: false },
  OPEN:              { label: 'Open',       bgClass: 'bg-red-600 dark:bg-red-500',            textClass: 'text-white',                                    icon: null,     spin: false },
  ACKNOWLEDGED:      { label: "Ack'd",      bgClass: 'bg-status-transition',                  textClass: 'text-white dark:text-[#1a1a2e]',                icon: null,     spin: false },
  RESOLVED:          { label: 'Resolved',   bgClass: 'bg-status-settled',                     textClass: 'text-white',                                    icon: '\u2713', spin: false },
  OK:                { label: 'OK',         bgClass: 'bg-status-settled',                     textClass: 'text-white',                                    icon: '\u2713', spin: false },
  DEVIATION:         { label: 'Deviation',  bgClass: 'bg-status-transition',                  textClass: 'text-white dark:text-[#1a1a2e]',                icon: null,     spin: false },
  MISSING:           { label: 'Missing',    bgClass: 'bg-red-600 dark:bg-red-500',            textClass: 'text-white',                                    icon: null,     spin: false },
};

/**
 * DA-specific status badge. Separate from the existing StatusBadge to avoid
 * conflating delivery statuses with alert/import/direction statuses.
 * Same pill visual pattern (icon + text), different type contract.
 */
export function DaStatusBadge({ status, size = 'sm', className }: DaStatusBadgeProps) {
  const config = STATUS_CONFIG[status];

  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-full font-medium',
        config.bgClass,
        config.textClass,
        size === 'sm' ? 'px-1.5 py-0.5 text-xs' : 'px-2 py-1 text-sm',
        className,
      )}
      aria-label={`Status: ${config.label}`}
    >
      {config.icon !== null && (
        <span
          aria-hidden="true"
          className={cn(config.spin && 'inline-block animate-spin')}
        >
          {config.icon}
        </span>
      )}
      {config.label}
    </span>
  );
}
