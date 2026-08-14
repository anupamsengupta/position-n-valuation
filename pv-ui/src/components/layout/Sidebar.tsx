import { Link } from '@tanstack/react-router';
import { cn } from '@/lib/cn';

export interface SidebarProps {
  className?: string;
}

/**
 * Left sidebar navigation. Currently only has the dashboard link.
 */
export function Sidebar({ className }: SidebarProps) {
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
      <Link
        to="/dashboard/$portfolioId"
        params={{ portfolioId: 'WIND_DE' }}
        className="flex items-center gap-2 px-2 py-1.5 text-xs font-medium rounded
                   text-text-primary bg-interactive-row-selected
                   focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus"
        activeProps={{ 'aria-current': 'page' } as Record<string, string>}
      >
        <span aria-hidden="true">{'\u2261'}</span>
        Position &amp; PnL
      </Link>
    </nav>
  );
}
