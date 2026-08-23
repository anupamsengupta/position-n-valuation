import { useRef } from 'react';
import { Link, useMatchRoute } from '@tanstack/react-router';
import { cn } from '@/lib/cn';

interface DaTab {
  label: string;
  to: string;
  ariaLabel: string;
}

const DA_TABS: DaTab[] = [
  { label: 'Import', to: '/da/import', ariaLabel: 'Auction import' },
  { label: 'Settlement', to: '/da/settlement', ariaLabel: 'Settlement grid' },
  { label: 'Nominations', to: '/da/nominations', ariaLabel: 'Nomination comparison' },
  { label: 'Imbalance', to: '/da/imbalance', ariaLabel: 'Imbalance records' },
  { label: 'Fees', to: '/da/fees', ariaLabel: 'Exchange fees' },
  { label: 'Alerts', to: '/da/alerts', ariaLabel: 'Operational alerts' },
];

/**
 * Horizontal tab navigation for the Day-Ahead layout.
 * Uses roving tabindex with arrow key navigation.
 * Active tab is determined by matching the current route path.
 */
export function DaNavTabs() {
  const matchRoute = useMatchRoute();
  const tabRefs = useRef<(HTMLAnchorElement | null)[]>([]);

  // Find the active tab index based on route matching
  const activeIndex = DA_TABS.findIndex((tab) =>
    matchRoute({ to: tab.to, fuzzy: true }),
  );

  const handleKeyDown = (e: React.KeyboardEvent, currentIndex: number) => {
    let nextIndex: number | null = null;

    if (e.key === 'ArrowRight') {
      nextIndex = (currentIndex + 1) % DA_TABS.length;
    } else if (e.key === 'ArrowLeft') {
      nextIndex = (currentIndex - 1 + DA_TABS.length) % DA_TABS.length;
    } else if (e.key === 'Home') {
      nextIndex = 0;
    } else if (e.key === 'End') {
      nextIndex = DA_TABS.length - 1;
    }

    if (nextIndex !== null) {
      e.preventDefault();
      tabRefs.current[nextIndex]?.focus();
    }
  };

  return (
    <div
      role="tablist"
      aria-label="Day-Ahead sections"
      className="flex items-center gap-0 border-b border-border-default"
    >
      {DA_TABS.map((tab, idx) => {
        const isActive = idx === activeIndex;
        return (
          <Link
            key={tab.to}
            ref={(el: HTMLAnchorElement | null) => {
              tabRefs.current[idx] = el;
            }}
            to={tab.to}
            role="tab"
            aria-selected={isActive}
            aria-label={tab.ariaLabel}
            tabIndex={isActive ? 0 : -1}
            onKeyDown={(e) => handleKeyDown(e, idx)}
            className={cn(
              'px-4 py-2 text-xs font-medium transition-colors whitespace-nowrap',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus focus-visible:ring-inset',
              isActive
                ? 'text-text-primary border-b-2 border-interactive-focus -mb-px'
                : 'text-text-muted hover:text-text-secondary hover:bg-bg-secondary',
            )}
          >
            {tab.label}
          </Link>
        );
      })}
    </div>
  );
}
