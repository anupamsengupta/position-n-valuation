import { useEffect } from 'react';
import * as Tooltip from '@radix-ui/react-tooltip';
import { cn } from '@/lib/cn';
import { usePortfolios } from '@/hooks/usePortfolios';
import { useUserPreferences } from '@/hooks/useUserPreferences';
import { useDaAlertCounts } from '@/hooks/useDaQueries';
import { SidebarSection } from './SidebarSection';
import { SidebarLink } from './SidebarLink';
import { AlertBadge } from './AlertBadge';
import {
  ChartIcon,
  ZapIcon,
  ClockIcon,
  TrendingIcon,
  BarChartIcon,
  BellIcon,
  ChevronIcon,
} from './SidebarIcons';

export interface SidebarProps {
  className?: string;
}

/**
 * Left sidebar navigation with persona-aware sections.
 *
 * Sections:
 * - DASHBOARDS: P&L Overview with portfolio sub-links
 * - INSTRUMENTS: Day-Ahead (with sub-pages), Intraday/PPA/Forwards (coming)
 * - OPERATIONS: Alerts with live badge
 *
 * Collapsible via Ctrl+B or the toggle button (48px icon-only vs 220px expanded).
 */
export function Sidebar({ className }: SidebarProps) {
  const collapsed = useUserPreferences((s) => s.sidebarCollapsed);
  const toggleSidebarCollapsed = useUserPreferences((s) => s.toggleSidebarCollapsed);
  const { data: portfolios, isLoading: portfoliosLoading } = usePortfolios();
  const { data: alertCounts } = useDaAlertCounts('OPEN');

  // Derive alert badge values from the counts map
  const totalAlerts = alertCounts
    ? Object.values(alertCounts).reduce((sum, n) => sum + n, 0)
    : 0;
  const maxSeverity: 'CRITICAL' | 'WARNING' | 'INFO' | null = alertCounts
    ? (alertCounts['CRITICAL'] ?? 0) > 0
      ? 'CRITICAL'
      : (alertCounts['WARNING'] ?? 0) > 0
        ? 'WARNING'
        : (alertCounts['INFO'] ?? 0) > 0
          ? 'INFO'
          : null
    : null;

  // Ctrl+B keyboard shortcut for sidebar collapse toggle
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if ((e.ctrlKey || e.metaKey) && e.key === 'b') {
        e.preventDefault();
        toggleSidebarCollapsed();
      }
    }
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [toggleSidebarCollapsed]);

  return (
    <Tooltip.Provider delayDuration={300}>
      <nav
        aria-label="Main navigation"
        className={cn(
          'shrink-0 border-r border-border-default bg-bg-secondary',
          'flex flex-col transition-[width] duration-200 ease-in-out overflow-hidden',
          collapsed ? 'w-12' : 'w-[220px]',
          className,
        )}
      >
        {/* Brand / collapse toggle */}
        <div
          className={cn(
            'flex items-center border-b border-border-default',
            collapsed ? 'justify-center px-1 py-2' : 'justify-between px-3 py-2',
          )}
        >
          {!collapsed && (
            <span className="text-sm font-bold text-text-primary truncate">
              PV Platform
            </span>
          )}
          <button
            type="button"
            onClick={toggleSidebarCollapsed}
            className={cn(
              'p-1 rounded text-text-muted hover:text-text-primary hover:bg-bg-tertiary',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus',
              'transition-colors',
            )}
            aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
            title="Toggle sidebar (Ctrl+B)"
          >
            <ChevronIcon
              className={cn(
                'w-4 h-4 transition-transform duration-200',
                collapsed && 'rotate-180',
              )}
            />
          </button>
        </div>

        {/* Scrollable navigation area */}
        <div className="flex-1 overflow-y-auto overflow-x-hidden py-2 px-1.5 flex flex-col gap-3">
          {/* DASHBOARDS section */}
          <SidebarSection label="Dashboards" sectionKey="dashboards" collapsed={collapsed}>
            <SidebarLink
              to="/dashboard/$portfolioId"
              params={{ portfolioId: portfolios?.[0] ?? 'WIND_DE' }}
              icon={ChartIcon}
              label="P&L Overview"
              collapsed={collapsed}
            />
            {/* Portfolio sub-links (indented) */}
            {!collapsed && portfoliosLoading && (
              <div className="pl-7 py-1 text-[10px] text-text-muted" aria-busy="true">
                Loading...
              </div>
            )}
            {!collapsed &&
              portfolios?.map((portfolioId) => (
                <SidebarLink
                  key={portfolioId}
                  to="/dashboard/$portfolioId"
                  params={{ portfolioId }}
                  icon={ChartIcon}
                  label={portfolioId}
                  indent
                  collapsed={collapsed}
                />
              ))}
          </SidebarSection>

          {/* INSTRUMENTS section */}
          <SidebarSection label="Instruments" sectionKey="instruments" collapsed={collapsed}>
            {/* Day-Ahead parent */}
            <SidebarLink
              to="/da/import"
              icon={ZapIcon}
              label="Day-Ahead"
              collapsed={collapsed}
            />
            {/* DA sub-links (indented) */}
            {!collapsed && (
              <>
                <SidebarLink to="/da/import" icon={ZapIcon} label="Import" indent collapsed={collapsed} />
                <SidebarLink to="/da/settlement" icon={ZapIcon} label="Settlement" indent collapsed={collapsed} />
                <SidebarLink to="/da/nominations" icon={ZapIcon} label="Nominations" indent collapsed={collapsed} />
                <SidebarLink to="/da/imbalance" icon={ZapIcon} label="Imbalance" indent collapsed={collapsed} />
                <SidebarLink to="/da/fees" icon={ZapIcon} label="Fees" indent collapsed={collapsed} />
              </>
            )}

            {/* Coming soon */}
            <SidebarLink
              to="/intraday"
              icon={ClockIcon}
              label="Intraday"
              disabled
              disabledLabel="(coming)"
              collapsed={collapsed}
            />
            <SidebarLink
              to="/ppa"
              icon={TrendingIcon}
              label="PPA"
              disabled
              disabledLabel="(coming)"
              collapsed={collapsed}
            />
            <SidebarLink
              to="/forwards"
              icon={BarChartIcon}
              label="Forwards"
              disabled
              disabledLabel="(coming)"
              collapsed={collapsed}
            />
          </SidebarSection>

          {/* OPERATIONS section */}
          <SidebarSection label="Operations" sectionKey="operations" collapsed={collapsed}>
            <div className="relative">
              <SidebarLink
                to="/da/alerts"
                icon={BellIcon}
                label="Alerts"
                badge={
                  <AlertBadge
                    count={totalAlerts}
                    maxSeverity={maxSeverity}
                    collapsed={collapsed}
                  />
                }
                collapsed={collapsed}
              />
              {collapsed && (
                <AlertBadge
                  count={totalAlerts}
                  maxSeverity={maxSeverity}
                  collapsed
                />
              )}
            </div>
          </SidebarSection>
        </div>
      </nav>
    </Tooltip.Provider>
  );
}
