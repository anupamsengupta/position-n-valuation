import { useCallback, useEffect, useMemo, useRef } from 'react';
import { PortfolioCard } from './PortfolioCard';
import { SkeletonCard } from '@/components/primitives/SkeletonRow';
import { usePortfolios } from '@/hooks/usePortfolios';
import { useAllPortfolioSummaries } from '@/hooks/useDashboardQueries';
import { KNOWN_PORTFOLIOS } from '@/api/portfolios';
import { parseNumericValue } from '@/lib/numberUtils';
import type { PortfolioSummaryDto } from '@/schemas/api';

export interface PortfolioCardStripProps {
  selectedPortfolioId: string;
  rangeStart: string;
  rangeEnd: string;
  onSelect: (id: string) => void;
}

const FALLBACK_PORTFOLIO_IDS = KNOWN_PORTFOLIOS.map((p) => p.portfolioId);

/**
 * Horizontal scrollable strip of portfolio summary cards.
 * Sorted by absolute total value (heaviest first).
 * Keyboard: Arrow Left/Right to navigate, Enter/Space to select, Home/End for first/last.
 */
export function PortfolioCardStrip({
  selectedPortfolioId,
  rangeStart,
  rangeEnd,
  onSelect,
}: PortfolioCardStripProps) {
  const { data: serverPortfolios } = usePortfolios();
  const portfolioIds =
    serverPortfolios && serverPortfolios.length > 0 ? serverPortfolios : FALLBACK_PORTFOLIO_IDS;

  const summaryResults = useAllPortfolioSummaries(portfolioIds, rangeStart, rangeEnd);
  const cardRefs = useRef<Map<string, HTMLDivElement>>(new Map());
  const focusedIndex = useRef(0);

  // Build sorted portfolio list by |totalPortfolioValue| descending
  const sortedPortfolios = useMemo(() => {
    const entries = portfolioIds.map((id, i) => {
      const result = summaryResults[i];
      const summaries = result?.data as PortfolioSummaryDto[] | undefined;
      const absTotal = summaries
        ? summaries.reduce(
            (sum, s) => sum + Math.abs(parseNumericValue(s.totalPortfolioValue) ?? 0),
            0,
          )
        : 0;
      return { id, summaries, isLoading: result?.isLoading ?? true, absTotal };
    });
    // Sort by absolute total value descending; keep stable order for equal values
    return entries.sort((a, b) => b.absTotal - a.absTotal);
  }, [portfolioIds, summaryResults]);

  const allLoading = sortedPortfolios.every((p) => p.isLoading);

  // Auto-select: if current selection is not in the list, select the first (highest value)
  useEffect(() => {
    if (allLoading) return;
    if (sortedPortfolios.length === 0) return;
    const inList = sortedPortfolios.some((p) => p.id === selectedPortfolioId);
    if (!inList) {
      onSelect(sortedPortfolios[0]!.id);
    }
  }, [allLoading, sortedPortfolios, selectedPortfolioId, onSelect]);

  // Scroll selected card into view
  useEffect(() => {
    const el = cardRefs.current.get(selectedPortfolioId);
    el?.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
  }, [selectedPortfolioId]);

  // Keyboard navigation (roving tabIndex)
  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      const ids = sortedPortfolios.map((p) => p.id);
      let idx = focusedIndex.current;

      switch (e.key) {
        case 'ArrowRight':
          e.preventDefault();
          idx = Math.min(idx + 1, ids.length - 1);
          break;
        case 'ArrowLeft':
          e.preventDefault();
          idx = Math.max(idx - 1, 0);
          break;
        case 'Home':
          e.preventDefault();
          idx = 0;
          break;
        case 'End':
          e.preventDefault();
          idx = ids.length - 1;
          break;
        case 'Enter':
        case ' ':
          e.preventDefault();
          onSelect(ids[idx]!);
          return;
        default:
          return;
      }

      focusedIndex.current = idx;
      const el = cardRefs.current.get(ids[idx]!);
      el?.focus();
    },
    [sortedPortfolios, onSelect],
  );

  // Keep focusedIndex in sync with selection
  useEffect(() => {
    const idx = sortedPortfolios.findIndex((p) => p.id === selectedPortfolioId);
    if (idx >= 0) focusedIndex.current = idx;
  }, [selectedPortfolioId, sortedPortfolios]);

  if (allLoading) {
    return (
      <div
        role="listbox"
        aria-label="Portfolio summaries"
        aria-orientation="horizontal"
        aria-busy="true"
        className="flex gap-3 overflow-x-auto scroll-smooth snap-x snap-mandatory pb-2"
      >
        {[0, 1, 2].map((i) => (
          <div key={i} className="w-[220px] min-w-[220px] snap-start">
            <SkeletonCard />
          </div>
        ))}
      </div>
    );
  }

  return (
    <div
      role="listbox"
      aria-label="Portfolio summaries"
      aria-orientation="horizontal"
      className="flex gap-3 overflow-x-auto scroll-smooth snap-x snap-mandatory pb-2"
    >
      {sortedPortfolios.map((portfolio) => (
        <PortfolioCard
          key={portfolio.id}
          ref={(el) => {
            if (el) cardRefs.current.set(portfolio.id, el);
            else cardRefs.current.delete(portfolio.id);
          }}
          portfolioId={portfolio.id}
          summaries={portfolio.summaries}
          isSelected={portfolio.id === selectedPortfolioId}
          isLoading={portfolio.isLoading}
          onClick={() => onSelect(portfolio.id)}
          tabIndex={portfolio.id === selectedPortfolioId ? 0 : -1}
          onKeyDown={handleKeyDown}
        />
      ))}
    </div>
  );
}
