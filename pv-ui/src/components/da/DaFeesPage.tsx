import { useDaFees } from '@/hooks/useDaQueries';
import { useDaFilters } from '@/hooks/useDaFilters';
import { DaFeeBreakdownTable } from './DaFeeBreakdownTable';
import { SkeletonTable } from '@/components/primitives/SkeletonRow';
import { EmptyState } from '@/components/primitives/EmptyState';
import { ErrorBoundary } from '@/components/primitives/ErrorBoundary';
import { cn } from '@/lib/cn';

/**
 * DA Fees page. Reads delivery day from useDaFilters and displays
 * the exchange fee breakdown table with metadata.
 */
export function DaFeesPage() {
  const deliveryDay = useDaFilters((s) => s.deliveryDay);
  const setDeliveryDay = useDaFilters((s) => s.setDeliveryDay);
  const feesQuery = useDaFees(deliveryDay);

  return (
    <div className="space-y-4">
      {/* Header with delivery day picker */}
      <div className="flex flex-wrap items-center gap-4">
        <h2 className="text-lg font-semibold text-text-primary">
          Exchange Fees
        </h2>
        <div className="flex items-center gap-2">
          <label
            htmlFor="fees-delivery-day"
            className="text-xs font-medium text-text-secondary"
          >
            Delivery Day
          </label>
          <input
            id="fees-delivery-day"
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
      </div>

      {/* Fee data */}
      <ErrorBoundary>
        {feesQuery.isLoading && (
          <SkeletonTable rows={6} columns={[30, 20, 25, 25]} />
        )}

        {feesQuery.isError && (
          <div role="alert" className="p-4 text-sm text-numeric-negative">
            Failed to load fee data. {feesQuery.error?.message}
          </div>
        )}

        {feesQuery.isSuccess && feesQuery.data && (
          <div className="space-y-4">
            {/* Fee schedule metadata */}
            <div
              className={cn(
                'flex flex-wrap items-center gap-4 px-3 py-2 text-xs',
                'rounded border border-border-default bg-bg-secondary',
              )}
            >
              <span className="text-text-secondary">
                Fee Schedule Effective:{' '}
                <span className="font-medium text-text-primary">
                  {feesQuery.data.feeScheduleEffectiveDate}
                </span>
              </span>
              {feesQuery.data.memberTier !== null && (
                <span className="text-text-secondary">
                  Member Tier:{' '}
                  <span className="font-medium text-text-primary">
                    {feesQuery.data.memberTier}
                  </span>
                </span>
              )}
            </div>

            {/* Fee breakdown table */}
            {feesQuery.data.items.length === 0 ? (
              <EmptyState message="No fee items for this delivery day." />
            ) : (
              <DaFeeBreakdownTable data={feesQuery.data} />
            )}

            {/* Explanatory note */}
            <p className="text-xs text-text-muted px-1">
              Fees are computed on gross (absolute) volume. Net buy/sell
              direction does not reduce the fee base.
            </p>
          </div>
        )}
      </ErrorBoundary>
    </div>
  );
}
