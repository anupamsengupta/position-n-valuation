export interface RowCheckboxProps {
  checked: boolean;
  onChange: () => void;
  label: string;
}

/**
 * Checkbox for individual row selection in grids.
 * Sized to fit 24px row height.
 */
export function RowCheckbox({ checked, onChange, label }: RowCheckboxProps) {
  return (
    <input
      type="checkbox"
      checked={checked}
      onChange={onChange}
      onClick={(e) => e.stopPropagation()}
      aria-label={label}
      className="h-3.5 w-3.5 cursor-pointer accent-interactive-focus"
    />
  );
}
