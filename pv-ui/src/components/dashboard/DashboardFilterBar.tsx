import { useCallback, useEffect } from 'react';
import { useNavigate } from '@tanstack/react-router';
import { cn } from '@/lib/cn';
import { GranularityToggle } from '@/components/primitives/GranularityToggle';
import { useDashboardFilters, type QuickFilterPreset } from '@/hooks/useDashboardFilters';
import { getMonthRange, getYearRange, getYearSpanRange } from '@/lib/dateUtils';
import { KNOWN_PORTFOLIOS } from '@/api/portfolios';
import { usePortfolios } from '@/hooks/usePortfolios';
import type { TimeGranularity } from '@/schemas/types';

// ---------------------------------------------------------------------------
// NavButton — shared chevron button
// ---------------------------------------------------------------------------

function NavButton({
  direction,
  onClick,
  label,
}: {
  direction: 'prev' | 'next';
  onClick: () => void;
  label: string;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      className={cn(
        'px-1.5 py-0.5 text-xs text-text-secondary rounded',
        'hover:bg-bg-secondary transition-colors',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus',
      )}
    >
      {direction === 'prev' ? '\u2039' : '\u203A'}
    </button>
  );
}

// ---------------------------------------------------------------------------
// PortfolioSelect
// ---------------------------------------------------------------------------

function PortfolioSelect({
  value,
  onChange,
  portfolios,
}: {
  value: string;
  onChange: (id: string) => void;
  portfolios: string[];
}) {
  return (
    <select
      aria-label="Portfolio"
      value={value}
      onChange={(e) => onChange(e.target.value)}
      className={cn(
        'px-2 py-1 text-xs font-medium rounded border border-border-default',
        'bg-bg-primary text-text-primary',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus',
      )}
    >
      {portfolios.map((id) => (
        <option key={id} value={id}>
          {id}
        </option>
      ))}
    </select>
  );
}

// ---------------------------------------------------------------------------
// DateRangeControls — adaptive date navigation
// ---------------------------------------------------------------------------

function YearMonthPicker({
  rangeStart,
  onNavigate,
}: {
  rangeStart: string;
  onNavigate: (range: { rangeStart: string; rangeEnd: string }) => void;
}) {
  const parts = rangeStart.split('-');
  const year = Number(parts[0]);
  const month = Number(parts[1]);

  const monthName = new Date(year, month - 1).toLocaleString('en-GB', { month: 'long' });

  const goPrev = () => {
    const m = month === 1 ? 12 : month - 1;
    const y = month === 1 ? year - 1 : year;
    onNavigate(getMonthRange(y, m));
  };
  const goNext = () => {
    const m = month === 12 ? 1 : month + 1;
    const y = month === 12 ? year + 1 : year;
    onNavigate(getMonthRange(y, m));
  };

  return (
    <div role="group" aria-label="Month navigation" className="inline-flex items-center gap-1">
      <NavButton direction="prev" onClick={goPrev} label="Previous month" />
      <span className="text-xs font-medium text-text-primary min-w-[100px] text-center">
        {year} / {monthName}
      </span>
      <NavButton direction="next" onClick={goNext} label="Next month" />
    </div>
  );
}

function YearPicker({
  rangeStart,
  onNavigate,
}: {
  rangeStart: string;
  onNavigate: (range: { rangeStart: string; rangeEnd: string }) => void;
}) {
  const year = Number(rangeStart.split('-')[0]);

  return (
    <div role="group" aria-label="Year navigation" className="inline-flex items-center gap-1">
      <NavButton direction="prev" onClick={() => onNavigate(getYearRange(year - 1))} label="Previous year" />
      <span className="text-xs font-medium text-text-primary min-w-[40px] text-center">
        {year}
      </span>
      <NavButton direction="next" onClick={() => onNavigate(getYearRange(year + 1))} label="Next year" />
    </div>
  );
}

function YearRangePicker({
  rangeStart,
  rangeEnd,
  onNavigate,
}: {
  rangeStart: string;
  rangeEnd: string;
  onNavigate: (range: { rangeStart: string; rangeEnd: string }) => void;
}) {
  const startYear = Number(rangeStart.split('-')[0]);
  const endYear = Number(rangeEnd.split('-')[0]);

  return (
    <div role="group" aria-label="Year range navigation" className="inline-flex items-center gap-1">
      <NavButton
        direction="prev"
        onClick={() => onNavigate(getYearSpanRange(startYear - 1, endYear - 1))}
        label="Shift range earlier"
      />
      <span className="text-xs font-medium text-text-primary min-w-[80px] text-center">
        {startYear} – {endYear}
      </span>
      <NavButton
        direction="next"
        onClick={() => onNavigate(getYearSpanRange(startYear + 1, endYear + 1))}
        label="Shift range later"
      />
    </div>
  );
}

function DateRangeControls({
  granularity,
  dateRange,
  onNavigate,
}: {
  granularity: TimeGranularity;
  dateRange: { rangeStart: string; rangeEnd: string };
  onNavigate: (range: { rangeStart: string; rangeEnd: string }) => void;
}) {
  switch (granularity) {
    case 'DAILY':
    case 'WEEKLY':
      return <YearMonthPicker rangeStart={dateRange.rangeStart} onNavigate={onNavigate} />;
    case 'MONTHLY':
      return <YearPicker rangeStart={dateRange.rangeStart} onNavigate={onNavigate} />;
    case 'YEARLY':
      return (
        <YearRangePicker
          rangeStart={dateRange.rangeStart}
          rangeEnd={dateRange.rangeEnd}
          onNavigate={onNavigate}
        />
      );
  }
}

// ---------------------------------------------------------------------------
// QuickFilters
// ---------------------------------------------------------------------------

const QUICK_PRESETS: { preset: QuickFilterPreset; label: string }[] = [
  { preset: 'THIS_MONTH', label: 'This Month' },
  { preset: 'THIS_QUARTER', label: 'This Qtr' },
  { preset: 'THIS_YEAR', label: 'This Year' },
  { preset: 'NEXT_YEAR', label: 'Next Year' },
];

function QuickFilters({ onApply }: { onApply: (preset: QuickFilterPreset) => void }) {
  return (
    <div role="group" aria-label="Quick date filters" className="inline-flex items-center gap-1">
      {QUICK_PRESETS.map(({ preset, label }) => (
        <button
          key={preset}
          type="button"
          onClick={() => onApply(preset)}
          className={cn(
            'px-2 py-0.5 text-xs font-medium rounded-full',
            'border border-border-default text-text-secondary',
            'hover:bg-bg-secondary transition-colors',
            'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus',
          )}
        >
          {label}
        </button>
      ))}
    </div>
  );
}

// ---------------------------------------------------------------------------
// DashboardFilterBar — main export
// ---------------------------------------------------------------------------

const FALLBACK_PORTFOLIO_IDS = KNOWN_PORTFOLIOS.map((p) => p.portfolioId);

export function DashboardFilterBar() {
  const {
    portfolioId,
    granularity,
    dateRange,
    setPortfolioId,
    setGranularity,
    setDateRange,
    applyQuickFilter,
  } = useDashboardFilters();

  const navigate = useNavigate();
  const { data: serverPortfolios } = usePortfolios();

  // Use server data when available, fall back to static list
  const portfolios = serverPortfolios && serverPortfolios.length > 0
    ? serverPortfolios
    : FALLBACK_PORTFOLIO_IDS;

  // If current selection isn't in the list, auto-select first
  useEffect(() => {
    if (portfolios.length > 0 && !portfolios.includes(portfolioId)) {
      const first = portfolios[0]!;
      setPortfolioId(first);
      navigate({ to: '/dashboard/$portfolioId', params: { portfolioId: first } });
    }
  }, [portfolios, portfolioId, setPortfolioId, navigate]);

  const handlePortfolioChange = useCallback(
    (id: string) => {
      setPortfolioId(id);
      navigate({ to: '/dashboard/$portfolioId', params: { portfolioId: id } });
    },
    [setPortfolioId, navigate],
  );

  return (
    <div
      role="toolbar"
      aria-label="Dashboard filters"
      className={cn(
        'flex flex-wrap items-center gap-3 px-3 py-2',
        'rounded border border-border-default bg-bg-secondary',
      )}
    >
      <PortfolioSelect value={portfolioId} onChange={handlePortfolioChange} portfolios={portfolios} />

      <div className="w-px h-5 bg-border-default" aria-hidden="true" />

      <GranularityToggle value={granularity} onChange={setGranularity} />

      <div className="w-px h-5 bg-border-default" aria-hidden="true" />

      <DateRangeControls
        granularity={granularity}
        dateRange={dateRange}
        onNavigate={setDateRange}
      />

      <div className="w-px h-5 bg-border-default" aria-hidden="true" />

      <QuickFilters onApply={applyQuickFilter} />
    </div>
  );
}
