import { useRef } from 'react';
import { cn } from '@/lib/cn';

export type ViewLayout = 'vertical' | 'horizontal';

export interface ViewLayoutToggleProps {
  value: ViewLayout;
  onChange: (value: ViewLayout) => void;
}

const OPTIONS: ViewLayout[] = ['vertical', 'horizontal'];

const LABELS: Record<ViewLayout, string> = {
  vertical: 'Vertical',
  horizontal: 'Horizontal',
};

const ARIA_LABELS: Record<ViewLayout, string> = {
  vertical: 'Vertical layout — one row per interval',
  horizontal: 'Horizontal layout — intervals as columns',
};

/**
 * Two-option radiogroup toggling between vertical (row-per-interval) and
 * horizontal (row-per-measure, intervals as columns) grid layout.
 * Follows the SubGranularityToggle pattern: roving tabindex, arrow-key nav.
 */
export function ViewLayoutToggle({ value, onChange }: ViewLayoutToggleProps) {
  const buttonRefs = useRef<(HTMLButtonElement | null)[]>([]);

  const handleKeyDown = (e: React.KeyboardEvent, currentIndex: number) => {
    let nextIndex: number | null = null;
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') {
      nextIndex = (currentIndex + 1) % OPTIONS.length;
    } else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
      nextIndex = (currentIndex - 1 + OPTIONS.length) % OPTIONS.length;
    }
    if (nextIndex !== null) {
      e.preventDefault();
      const opt = OPTIONS[nextIndex];
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
      aria-label="Grid layout"
    >
      {OPTIONS.map((opt, idx) => (
        <button
          key={opt}
          ref={(el) => { buttonRefs.current[idx] = el; }}
          type="button"
          role="radio"
          aria-checked={value === opt}
          aria-label={ARIA_LABELS[opt]}
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
          {LABELS[opt]}
        </button>
      ))}
    </div>
  );
}
