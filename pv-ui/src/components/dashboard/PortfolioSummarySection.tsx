import { KpiTile } from '@/components/primitives/KpiTile';
import { SkeletonCard } from '@/components/primitives/SkeletonRow';
import { EmptyState } from '@/components/primitives/EmptyState';
import type { PortfolioSummaryDto } from '@/schemas/api';

export interface PortfolioSummarySectionProps {
  data: PortfolioSummaryDto[] | undefined;
  isLoading: boolean;
  isError: boolean;
  error?: Error | null;
}

/**
 * L1: Portfolio summary cards -- one card per currency.
 * Shows realized PnL, unrealized MtM (indicative), total value, and position volumes.
 */
export function PortfolioSummarySection({
  data,
  isLoading,
  isError,
  error,
}: PortfolioSummarySectionProps) {
  if (isLoading) {
    return (
      <section aria-label="Portfolio Summary" aria-busy="true">
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <SkeletonCard />
          <SkeletonCard />
          <SkeletonCard />
        </div>
      </section>
    );
  }

  if (isError) {
    return (
      <section aria-label="Portfolio Summary">
        <div
          role="alert"
          className="rounded-lg border border-numeric-negative/30 bg-numeric-negative/5 p-4 text-sm text-numeric-negative"
        >
          Failed to load portfolio summary: {error?.message ?? 'Unknown error'}
        </div>
      </section>
    );
  }

  if (!data || data.length === 0) {
    return (
      <section aria-label="Portfolio Summary">
        <EmptyState message="No position data available for this portfolio." />
      </section>
    );
  }

  return (
    <section aria-label="Portfolio Summary">
      <div role="list" aria-label="Portfolio cards" className="flex flex-wrap gap-4">
        {data.map((summary) => (
          <div
            key={summary.currency}
            role="listitem"
            aria-label={`${summary.currency} summary`}
            className="min-w-[340px] flex-1 rounded-lg border border-border-default bg-bg-secondary p-4 space-y-3"
          >
            <div className="flex items-center justify-between">
              <span className="text-sm font-semibold text-text-primary">
                {summary.currency}
              </span>
              <span className="text-xs text-text-muted">
                As of {new Date(summary.dataAsOf).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })}
              </span>
            </div>

            <div className="grid grid-cols-2 gap-x-6 gap-y-2">
              <KpiTile
                label="Realized PnL"
                value={summary.realizedPnl}
                precision="MONETARY"
                currency={summary.currency}
              />
              <div>
                <KpiTile
                  label="Unrealized MtM"
                  value={summary.unrealizedMtm}
                  precision="MONETARY"
                  currency={summary.currency}
                />
                <span className="text-xs text-text-muted italic">Current MtM (indicative)</span>
              </div>
            </div>

            <div className="border-t border-border-grid pt-2">
              <KpiTile
                label="Total Portfolio Value"
                value={summary.totalPortfolioValue}
                precision="MONETARY"
                currency={summary.currency}
              />
            </div>

            <div className="grid grid-cols-2 gap-x-6 gap-y-2 border-t border-border-grid pt-2">
              <div className="space-y-1">
                <span className="text-xs text-text-muted">Settled</span>
                <div className="flex gap-4">
                  <KpiTile label="MW" value={summary.settledNetMw} precision="MW" />
                  <KpiTile label="MWh" value={summary.settledNetMwh} precision="MWH" />
                </div>
              </div>
              <div className="space-y-1">
                <span className="text-xs text-text-muted">Forward</span>
                <div className="flex gap-4">
                  <KpiTile label="MW" value={summary.forwardNetMw} precision="MW" />
                  <KpiTile label="MWh" value={summary.forwardNetMwh} precision="MWH" />
                </div>
              </div>
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}
