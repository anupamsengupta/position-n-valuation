import { useState } from 'react';

export interface DaySelectionBannerProps {
  selectedCount: number;
  dayLabels: string[];
  onClear: () => void;
}

/**
 * Banner shown above sub-daily grids when 2+ days are checkbox-selected.
 * Announces the combined view status to screen readers.
 */
export function DaySelectionBanner({
  selectedCount,
  dayLabels,
  onClear,
}: DaySelectionBannerProps) {
  const [expanded, setExpanded] = useState(false);
  const showToggle = dayLabels.length > 5;
  const visibleLabels = expanded ? dayLabels : dayLabels.slice(0, 5);

  return (
    <div
      role="status"
      className="bg-bg-info border border-border-info rounded px-3 py-2 text-xs text-text-primary flex items-center justify-between gap-2"
    >
      <div className="flex items-center gap-2 flex-wrap">
        <span className="font-medium">
          Showing combined view for {selectedCount} days
        </span>
        {visibleLabels.map((label) => (
          <span
            key={label}
            className="inline-block px-1.5 py-0.5 bg-bg-secondary border border-border-default rounded text-[10px] text-text-secondary"
          >
            {label}
          </span>
        ))}
        {showToggle && (
          <button
            type="button"
            onClick={() => setExpanded(!expanded)}
            className="text-[10px] text-interactive-focus hover:underline focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-interactive-focus rounded"
          >
            {expanded ? 'Show less' : `+${dayLabels.length - 5} more`}
          </button>
        )}
      </div>
      <button
        type="button"
        onClick={onClear}
        className="text-[10px] text-text-secondary hover:text-text-primary whitespace-nowrap
                   focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-interactive-focus rounded"
      >
        Clear selection
      </button>
    </div>
  );
}
