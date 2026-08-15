import { Link } from '@tanstack/react-router';
import { cn } from '@/lib/cn';
import { usePortfolios } from '@/hooks/usePortfolios';

export interface SidebarProps {
  className?: string;
}

/**
 * Left sidebar navigation with portfolio links.
 * Fetches distinct portfolio IDs from the backend via usePortfolios().
 */
export function Sidebar({ className }: SidebarProps) {
  const { data: portfolios, isLoading } = usePortfolios();

  return (
    <nav
      aria-label="Main navigation"
      className={cn(
        'w-48 shrink-0 border-r border-border-default bg-bg-secondary',
        'flex flex-col p-3',
        className,
      )}
    >
      <div className="text-sm font-bold text-text-primary mb-4 px-2">
        PV Platform
      </div>
      <div className="text-[10px] font-semibold text-text-muted uppercase tracking-wider px-2 mb-1">
        Portfolios
      </div>
      {isLoading ? (
        <div className="px-2 py-1.5 text-xs text-text-muted" aria-busy="true">
          Loading...
        </div>
      ) : portfolios && portfolios.length > 0 ? (
        portfolios.map((portfolioId) => (
          <Link
            key={portfolioId}
            to="/dashboard/$portfolioId"
            params={{ portfolioId }}
            className={cn(
              'flex items-center gap-2 px-2 py-1.5 text-xs font-medium rounded',
              'text-text-primary hover:bg-interactive-row-selected',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus',
            )}
            activeProps={{ className: 'bg-interactive-row-selected' } as Record<string, string>}
          >
            <span aria-hidden="true">{'\u2261'}</span>
            {portfolioId}
          </Link>
        ))
      ) : (
        <div className="px-2 py-1.5 text-xs text-text-muted">
          No portfolios found
        </div>
      )}
    </nav>
  );
}
