import { useDaFilters } from '@/hooks/useDaFilters';
import { cn } from '@/lib/cn';

const ZONE_OPTIONS = [
  { value: 'DE_LU', label: 'DE/LU' },
  { value: 'FR', label: 'FR' },
  { value: 'AT', label: 'AT' },
] as const;

/**
 * Filter bar for DA settlement grid: delivery day picker and zone dropdown.
 * Reads/writes useDaFilters store.
 */
export function DaSettlementFilterBar() {
  const deliveryDay = useDaFilters((s) => s.deliveryDay);
  const zone = useDaFilters((s) => s.zone);
  const setDeliveryDay = useDaFilters((s) => s.setDeliveryDay);
  const setZone = useDaFilters((s) => s.setZone);

  return (
    <div
      role="toolbar"
      aria-label="Settlement filters"
      className={cn(
        'flex flex-wrap items-center gap-3 px-3 py-2',
        'rounded border border-border-default bg-bg-secondary',
      )}
    >
      {/* Delivery day */}
      <div className="flex items-center gap-2">
        <label
          htmlFor="settlement-delivery-day"
          className="text-xs font-medium text-text-secondary"
        >
          Delivery Day
        </label>
        <input
          id="settlement-delivery-day"
          type="date"
          value={deliveryDay}
          onChange={(e) => setDeliveryDay(e.target.value)}
          className={cn(
            'px-2 py-1 text-xs rounded border border-border-default',
            'bg-bg-primary text-text-primary',
            'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus',
          )}
        />
      </div>

      <div className="w-px h-5 bg-border-default" aria-hidden="true" />

      {/* Zone */}
      <div className="flex items-center gap-2">
        <label
          htmlFor="settlement-zone"
          className="text-xs font-medium text-text-secondary"
        >
          Zone
        </label>
        <select
          id="settlement-zone"
          value={zone}
          onChange={(e) => setZone(e.target.value)}
          className={cn(
            'px-2 py-1 text-xs rounded border border-border-default',
            'bg-bg-primary text-text-primary',
            'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus',
          )}
        >
          {ZONE_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
      </div>
    </div>
  );
}
