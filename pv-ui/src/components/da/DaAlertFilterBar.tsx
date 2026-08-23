import { cn } from '@/lib/cn';
import { useDaFilters } from '@/hooks/useDaFilters';
import type { AlertSeverity } from '@/schemas/daApi';

const STATUS_OPTIONS: { value: string; label: string }[] = [
  { value: '__all__', label: 'All Statuses' },
  { value: 'OPEN', label: 'Open' },
  { value: 'ACKNOWLEDGED', label: 'Acknowledged' },
  { value: 'RESOLVED', label: 'Resolved' },
];

const SEVERITY_OPTIONS: { value: AlertSeverity; label: string }[] = [
  { value: 'CRITICAL', label: 'Critical' },
  { value: 'WARNING', label: 'Warning' },
  { value: 'INFO', label: 'Info' },
];

const CATEGORY_OPTIONS: { value: string; label: string }[] = [
  { value: '__all__', label: 'All Categories' },
  { value: 'AUCTION_INGESTION', label: 'Auction Ingestion' },
  { value: 'DA_CLEARING_PRICES', label: 'Clearing Prices' },
  { value: 'NOMINATION_SCHEDULING', label: 'Nominations' },
  { value: 'IMBALANCE_SETTLEMENT', label: 'Imbalance' },
  { value: 'EXCHANGE_FEES', label: 'Exchange Fees' },
  { value: 'SETTLEMENT', label: 'Settlement' },
];

interface DaAlertFilterBarProps {
  onClear: () => void;
}

/**
 * Filter bar for the Alerts page.
 * Status dropdown, severity checkboxes, category dropdown.
 */
export function DaAlertFilterBar({ onClear }: DaAlertFilterBarProps) {
  const alertStatusFilter = useDaFilters((s) => s.alertStatusFilter);
  const alertSeverityFilter = useDaFilters((s) => s.alertSeverityFilter);
  const alertCategoryFilter = useDaFilters((s) => s.alertCategoryFilter);
  const setAlertFilters = useDaFilters((s) => s.setAlertFilters);

  const handleSeverityToggle = (sev: string) => {
    const current = alertSeverityFilter;
    const next = current.includes(sev)
      ? current.filter((s) => s !== sev)
      : [...current, sev];
    setAlertFilters({ severity: next });
  };

  const inputClass = cn(
    'px-2 py-0.5 text-xs rounded border border-border-default',
    'bg-bg-primary text-text-primary',
    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus',
  );

  const hasFilters =
    alertStatusFilter !== null ||
    alertSeverityFilter.length > 0 ||
    alertCategoryFilter !== null;

  return (
    <div
      role="toolbar"
      aria-label="Alert filters"
      className={cn(
        'flex flex-wrap items-center gap-3 px-3 py-2',
        'rounded border border-border-default bg-bg-secondary',
      )}
    >
      {/* Status */}
      <label className="inline-flex items-center gap-1.5 text-xs text-text-secondary">
        <span>Status</span>
        <select
          value={alertStatusFilter ?? '__all__'}
          onChange={(e) =>
            setAlertFilters({
              status: e.target.value === '__all__' ? null : e.target.value,
            })
          }
          className={inputClass}
        >
          {STATUS_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
      </label>

      <div className="w-px h-5 bg-border-default" aria-hidden="true" />

      {/* Severity checkboxes */}
      <fieldset className="flex items-center gap-2">
        <legend className="sr-only">Severity filter</legend>
        {SEVERITY_OPTIONS.map((sev) => (
          <label
            key={sev.value}
            className="inline-flex items-center gap-1 text-xs text-text-secondary cursor-pointer"
          >
            <input
              type="checkbox"
              checked={alertSeverityFilter.includes(sev.value)}
              onChange={() => handleSeverityToggle(sev.value)}
              className="h-3 w-3 rounded border-border-default"
            />
            <span>{sev.label}</span>
          </label>
        ))}
      </fieldset>

      <div className="w-px h-5 bg-border-default" aria-hidden="true" />

      {/* Category */}
      <label className="inline-flex items-center gap-1.5 text-xs text-text-secondary">
        <span>Category</span>
        <select
          value={alertCategoryFilter ?? '__all__'}
          onChange={(e) =>
            setAlertFilters({
              category: e.target.value === '__all__' ? null : e.target.value,
            })
          }
          className={inputClass}
        >
          {CATEGORY_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
      </label>

      {/* Clear button */}
      {hasFilters && (
        <>
          <div className="w-px h-5 bg-border-default" aria-hidden="true" />
          <button
            onClick={onClear}
            className="text-xs text-interactive-primary hover:underline"
          >
            Clear filters
          </button>
        </>
      )}
    </div>
  );
}
