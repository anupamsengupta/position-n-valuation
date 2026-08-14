import { useRef } from 'react';
import { cn } from '@/lib/cn';
import type { TimeGranularity, SubDailyGranularity } from '@/schemas/types';

export interface GranularityToggleProps {
  value: TimeGranularity;
  onChange: (g: TimeGranularity) => void;
  options?: TimeGranularity[];
}

const DEFAULT_OPTIONS: TimeGranularity[] = ['DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY'];
const LABELS: Record<TimeGranularity, string> = {
  DAILY: 'D',
  WEEKLY: 'W',
  MONTHLY: 'M',
  YEARLY: 'Y',
};
const ARIA_LABELS: Record<TimeGranularity, string> = {
  DAILY: 'Daily',
  WEEKLY: 'Weekly',
  MONTHLY: 'Monthly',
  YEARLY: 'Yearly',
};

/**
 * Segmented control for time granularity selection.
 * Keyboard: arrow keys cycle options and move focus (roving tabindex).
 */
export function GranularityToggle({
  value,
  onChange,
  options = DEFAULT_OPTIONS,
}: GranularityToggleProps) {
  const buttonRefs = useRef<(HTMLButtonElement | null)[]>([]);

  const handleKeyDown = (e: React.KeyboardEvent, currentIndex: number) => {
    let nextIndex: number | null = null;
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') {
      nextIndex = (currentIndex + 1) % options.length;
    } else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
      nextIndex = (currentIndex - 1 + options.length) % options.length;
    }
    if (nextIndex !== null) {
      e.preventDefault();
      const opt = options[nextIndex];
      if (opt) {
        onChange(opt);
        buttonRefs.current[nextIndex]?.focus();
      }
    }
  };

  return (
    <div
      className="inline-flex rounded-md border border-border-default overflow-hidden"
      role="radiogroup"
      aria-label="Time granularity"
    >
      {options.map((opt, idx) => (
        <button
          key={opt}
          ref={(el) => { buttonRefs.current[idx] = el; }}
          type="button"
          role="radio"
          aria-checked={value === opt}
          aria-label={ARIA_LABELS[opt]}
          tabIndex={value === opt ? 0 : -1}
          className={cn(
            'px-2.5 py-1 text-xs font-medium transition-colors',
            'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus',
            value === opt
              ? 'bg-interactive-focus text-white'
              : 'bg-bg-primary text-text-secondary hover:bg-bg-secondary',
          )}
          onClick={() => onChange(opt)}
          onKeyDown={(e) => handleKeyDown(e, idx)}
        >
          {LABELS[opt]}
        </button>
      ))}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Sub-daily variant
// ---------------------------------------------------------------------------

export interface SubGranularityToggleProps {
  value: SubDailyGranularity;
  onChange: (g: SubDailyGranularity) => void;
}

const SUB_OPTIONS: SubDailyGranularity[] = ['MIN_15', 'MIN_30', 'HOURLY'];
const SUB_LABELS: Record<SubDailyGranularity, string> = {
  MIN_15: '15m',
  MIN_30: '30m',
  HOURLY: '60m',
};
const SUB_ARIA_LABELS: Record<SubDailyGranularity, string> = {
  MIN_15: '15 minutes',
  MIN_30: '30 minutes',
  HOURLY: 'Hourly',
};

export function SubGranularityToggle({ value, onChange }: SubGranularityToggleProps) {
  const buttonRefs = useRef<(HTMLButtonElement | null)[]>([]);

  const handleKeyDown = (e: React.KeyboardEvent, currentIndex: number) => {
    let nextIndex: number | null = null;
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') {
      nextIndex = (currentIndex + 1) % SUB_OPTIONS.length;
    } else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
      nextIndex = (currentIndex - 1 + SUB_OPTIONS.length) % SUB_OPTIONS.length;
    }
    if (nextIndex !== null) {
      e.preventDefault();
      const opt = SUB_OPTIONS[nextIndex];
      if (opt) {
        onChange(opt);
        buttonRefs.current[nextIndex]?.focus();
      }
    }
  };

  return (
    <div
      className="inline-flex rounded-md border border-border-default overflow-hidden"
      role="radiogroup"
      aria-label="Sub-daily granularity"
    >
      {SUB_OPTIONS.map((opt, idx) => (
        <button
          key={opt}
          ref={(el) => { buttonRefs.current[idx] = el; }}
          type="button"
          role="radio"
          aria-checked={value === opt}
          aria-label={SUB_ARIA_LABELS[opt]}
          tabIndex={value === opt ? 0 : -1}
          className={cn(
            'px-2 py-1 text-xs font-medium transition-colors',
            'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus',
            value === opt
              ? 'bg-interactive-focus text-white'
              : 'bg-bg-primary text-text-secondary hover:bg-bg-secondary',
          )}
          onClick={() => onChange(opt)}
          onKeyDown={(e) => handleKeyDown(e, idx)}
        >
          {SUB_LABELS[opt]}
        </button>
      ))}
    </div>
  );
}
