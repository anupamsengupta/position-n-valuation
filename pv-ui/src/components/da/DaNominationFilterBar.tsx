import { cn } from '@/lib/cn';
import { useDaFilters } from '@/hooks/useDaFilters';
import { useDaBalancingGroups } from '@/hooks/useDaQueries';

/**
 * Filter bar for the Nominations page.
 * Delivery day picker + balancing group dropdown.
 * Reads/writes useDaFilters store.
 */
export function DaNominationFilterBar() {
  const deliveryDay = useDaFilters((s) => s.deliveryDay);
  const balancingGroupId = useDaFilters((s) => s.balancingGroupId);
  const setDeliveryDay = useDaFilters((s) => s.setDeliveryDay);
  const setBalancingGroupId = useDaFilters((s) => s.setBalancingGroupId);

  const bgQuery = useDaBalancingGroups();
  const balancingGroups = bgQuery.data ?? [];

  return (
    <div
      role="toolbar"
      aria-label="Nomination filters"
      className={cn(
        'flex flex-wrap items-center gap-3 px-3 py-2',
        'rounded border border-border-default bg-bg-secondary',
      )}
    >
      {/* Delivery day picker */}
      <label className="inline-flex items-center gap-1.5 text-xs text-text-secondary">
        <span>Delivery Day</span>
        <input
          type="date"
          value={deliveryDay}
          onChange={(e) => setDeliveryDay(e.target.value)}
          className={cn(
            'px-2 py-0.5 text-xs rounded border border-border-default',
            'bg-bg-primary text-text-primary',
            'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus',
          )}
        />
      </label>

      <div className="w-px h-5 bg-border-default" aria-hidden="true" />

      {/* Balancing group dropdown */}
      <label className="inline-flex items-center gap-1.5 text-xs text-text-secondary">
        <span>Balancing Group</span>
        <select
          value={balancingGroupId ?? '__all__'}
          onChange={(e) =>
            setBalancingGroupId(e.target.value === '__all__' ? null : e.target.value)
          }
          className={cn(
            'px-2 py-0.5 text-xs rounded border border-border-default',
            'bg-bg-primary text-text-primary',
            'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus',
          )}
        >
          <option value="__all__">All BGs</option>
          {balancingGroups.map((bg) => (
            <option key={bg.bgId} value={bg.bgId}>
              {bg.bgCode} ({bg.tsoArea})
            </option>
          ))}
        </select>
      </label>
    </div>
  );
}
