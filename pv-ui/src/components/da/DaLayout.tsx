import { Outlet } from '@tanstack/react-router';
import { DaNavTabs } from './DaNavTabs';
import { DaKpiStrip } from './DaKpiStrip';

/**
 * Parent layout for all Day-Ahead Exchange Spot routes.
 * Renders:
 * 1. Horizontal tab navigation (Import | Settlement | Nominations | ...)
 * 2. KPI summary strip (6 tiles)
 * 3. <Outlet /> for the active child route content
 */
export function DaLayout() {
  return (
    <div className="flex flex-col h-full">
      <DaNavTabs />
      <DaKpiStrip />
      <div className="flex-1 overflow-auto">
        <Outlet />
      </div>
    </div>
  );
}
