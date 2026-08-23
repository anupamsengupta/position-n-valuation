export interface HideZeroToggleProps {
  checked: boolean;
  onChange: (checked: boolean) => void;
}

/**
 * Checkbox toggle to hide rows where all numeric measures are zero.
 */
export function HideZeroToggle({ checked, onChange }: HideZeroToggleProps) {
  return (
    <label className="flex items-center gap-1.5 text-xs text-text-secondary cursor-pointer select-none">
      <input
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
        className="h-3.5 w-3.5 rounded border-border-default text-interactive-focus
                   focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus"
      />
      Hide zero rows
    </label>
  );
}
