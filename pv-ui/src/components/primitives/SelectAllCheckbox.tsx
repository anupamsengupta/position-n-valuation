import { useRef, useEffect } from 'react';

export interface SelectAllCheckboxProps {
  checked: boolean;
  indeterminate: boolean;
  onChange: () => void;
  totalCount: number;
  selectedCount: number;
}

/**
 * Tri-state checkbox for "select all" in grid headers.
 * Uses the native `indeterminate` property via ref.
 */
export function SelectAllCheckbox({
  checked,
  indeterminate,
  onChange,
  totalCount,
  selectedCount,
}: SelectAllCheckboxProps) {
  const ref = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (ref.current) {
      ref.current.indeterminate = indeterminate;
    }
  }, [indeterminate]);

  const label = checked
    ? `Deselect all ${totalCount} positions`
    : `Select all ${totalCount} positions`;

  return (
    <div className="flex items-center gap-1.5">
      <input
        ref={ref}
        type="checkbox"
        checked={checked}
        onChange={onChange}
        aria-label={label}
        className="h-3.5 w-3.5 cursor-pointer accent-interactive-focus"
      />
      {selectedCount > 0 && (
        <span className="text-[10px] text-text-muted whitespace-nowrap">
          {selectedCount}/{totalCount}
        </span>
      )}
    </div>
  );
}
