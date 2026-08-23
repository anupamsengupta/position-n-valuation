import { forwardRef } from 'react';
import { cn } from '@/lib/cn';
import { NumericCell } from '@/components/primitives/NumericCell';
import { SkeletonCard } from '@/components/primitives/SkeletonRow';
import { KNOWN_PORTFOLIOS } from '@/api/portfolios';
import type { PortfolioSummaryDto } from '@/schemas/api';
import { parseNumericValue } from '@/lib/numberUtils';

export interface PortfolioCardProps {
  portfolioId: string;
  summaries: PortfolioSummaryDto[] | undefined;
  isSelected: boolean;
  isLoading: boolean;
  onClick: () => void;
  tabIndex: number;
  onKeyDown: (e: React.KeyboardEvent) => void;
}

function lookupLabel(portfolioId: string): string {
  const known = KNOWN_PORTFOLIOS.find((p) => p.portfolioId === portfolioId);
  return known?.label ?? portfolioId;
}

/**
 * Compact summary card for a single portfolio.
 * Shows total value, realized PnL, unrealized MtM, and currency.
 */
export const PortfolioCard = forwardRef<HTMLDivElement, PortfolioCardProps>(
  function PortfolioCard(
    { portfolioId, summaries, isSelected, isLoading, onClick, tabIndex, onKeyDown },
    ref,
  ) {
    if (isLoading) {
      return (
        <div className="w-[220px] min-w-[220px] snap-start">
          <SkeletonCard />
        </div>
      );
    }

    // Aggregate across currencies for the total value shown on the card
    const primary = summaries?.[0];
    const totalValue = summaries
      ? summaries.reduce((sum, s) => sum + (parseNumericValue(s.totalPortfolioValue) ?? 0), 0)
      : null;
    const currency = primary?.currency ?? '';
    const label = lookupLabel(portfolioId);

    const ariaLabel = `Portfolio ${label}, total value ${totalValue !== null ? totalValue.toFixed(2) : 'no data'} ${currency}`;

    return (
      <div
        ref={ref}
        role="option"
        aria-selected={isSelected}
        aria-label={ariaLabel}
        tabIndex={tabIndex}
        onClick={onClick}
        onKeyDown={onKeyDown}
        className={cn(
          'w-[220px] min-w-[220px] snap-start',
          'rounded-lg border bg-bg-secondary p-3 space-y-2 cursor-pointer transition-all',
          isSelected
            ? 'border-interactive-focus ring-2 ring-interactive-focus/30 bg-bg-primary'
            : 'border-border-default hover:border-border-hover hover:shadow-sm',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus',
        )}
      >
        {/* Portfolio label */}
        <div className="text-xs font-semibold text-text-primary truncate">{label}</div>

        {/* Total value */}
        <div className="flex items-baseline justify-between">
          <NumericCell
            value={totalValue}
            precision="MONETARY"
            currency={currency}
            colorNegative
            className="text-sm font-bold"
          />
        </div>

        {/* Realized + Unrealized */}
        <div className="space-y-0.5">
          <div className="flex items-center justify-between">
            <span className="text-[10px] text-text-muted">Realized</span>
            <NumericCell
              value={primary?.realizedPnl}
              precision="MONETARY"
              colorNegative
              className="text-[11px]"
            />
          </div>
          <div className="flex items-center justify-between">
            <span className="text-[10px] text-text-muted">Unrealized</span>
            <NumericCell
              value={primary?.unrealizedMtm}
              precision="MONETARY"
              colorNegative
              className="text-[11px]"
            />
          </div>
        </div>

        {/* Currency badge */}
        {currency && (
          <div className="flex justify-end">
            <span className="text-[10px] font-medium text-text-muted bg-bg-tertiary px-1.5 py-0.5 rounded">
              {currency}
            </span>
          </div>
        )}
      </div>
    );
  },
);
