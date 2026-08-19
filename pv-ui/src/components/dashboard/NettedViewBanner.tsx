import { useState } from 'react';

export interface NettedViewBannerProps {
  selectedCount: number;
  selectedTradeIds: string[];
  onClear: () => void;
}

/**
 * Banner shown above L4 IntervalDetailPanel when 2+ positions are selected.
 * Announces the netted view status to screen readers.
 */
export function NettedViewBanner({
  selectedCount,
  selectedTradeIds,
  onClear,
}: NettedViewBannerProps) {
  const [expanded, setExpanded] = useState(false);
  const showToggle = selectedTradeIds.length > 3;
  const visibleIds = expanded ? selectedTradeIds : selectedTradeIds.slice(0, 3);

  return (
    <div
      role="status"
      className="bg-bg-info border border-border-info rounded px-3 py-2 text-xs text-text-primary flex items-center justify-between gap-2"
    >
      <div className="flex items-center gap-2 flex-wrap">
        <span className="font-medium">
          Showing netted view for {selectedCount} positions
        </span>
        {visibleIds.map((id) => (
          <span
            key={id}
            className="inline-block px-1.5 py-0.5 bg-bg-secondary border border-border-default rounded text-[10px] text-text-secondary"
          >
            {id}
          </span>
        ))}
        {showToggle && (
          <button
            type="button"
            onClick={() => setExpanded(!expanded)}
            className="text-[10px] text-interactive-focus hover:underline focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-interactive-focus rounded"
          >
            {expanded ? 'Show less' : `+${selectedTradeIds.length - 3} more`}
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
