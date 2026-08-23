import { lazy } from 'react';
import {
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
  redirect,
} from '@tanstack/react-router';
import { AppShell } from '@/components/layout/AppShell';
import { DashboardPage } from '@/components/dashboard/DashboardPage';

// Lazy-loaded DA layout for bundle splitting
const DaLayout = lazy(() =>
  import('@/components/da/DaLayout').then((m) => ({ default: m.DaLayout })),
);

// Lazy-loaded DA page components
const DaImportPage = lazy(() =>
  import('@/components/da/DaImportPage').then((m) => ({ default: m.DaImportPage })),
);
const DaSettlementPage = lazy(() =>
  import('@/components/da/DaSettlementPage').then((m) => ({ default: m.DaSettlementPage })),
);
const DaNominationPage = lazy(() =>
  import('@/components/da/DaNominationPage').then((m) => ({ default: m.DaNominationPage })),
);
const DaImbalancePage = lazy(() =>
  import('@/components/da/DaImbalancePage').then((m) => ({ default: m.DaImbalancePage })),
);
const DaFeesPage = lazy(() =>
  import('@/components/da/DaFeesPage').then((m) => ({ default: m.DaFeesPage })),
);
const DaAlertsPage = lazy(() =>
  import('@/components/da/DaAlertsPage').then((m) => ({ default: m.DaAlertsPage })),
);

// Root layout route
const rootRoute = createRootRoute({
  component: () => (
    <AppShell>
      <Outlet />
    </AppShell>
  ),
});

// Index route - redirects to default portfolio
const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  beforeLoad: () => {
    throw redirect({ to: '/dashboard/$portfolioId', params: { portfolioId: 'WIND_DE' } });
  },
});

// Dashboard route
const dashboardRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/dashboard/$portfolioId',
  component: function DashboardRoute() {
    const { portfolioId } = dashboardRoute.useParams();
    return <DashboardPage portfolioId={portfolioId} />;
  },
});

// ---------------------------------------------------------------------------
// Day-Ahead routes
// ---------------------------------------------------------------------------

const daLayoutRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/da',
  component: DaLayout,
});

// /da redirects to /da/import
const daIndexRoute = createRoute({
  getParentRoute: () => daLayoutRoute,
  path: '/',
  beforeLoad: () => {
    throw redirect({ to: '/da/import' });
  },
});

const daImportRoute = createRoute({
  getParentRoute: () => daLayoutRoute,
  path: '/import',
  component: function DaImportRoute() {
    return <DaImportPage />;
  },
});

const daImportDetailRoute = createRoute({
  getParentRoute: () => daLayoutRoute,
  path: '/import/$sessionId',
  component: function DaImportDetailRoute() {
    const { sessionId } = daImportDetailRoute.useParams();
    return <DaImportPage sessionId={sessionId} />;
  },
});

const daSettlementRoute = createRoute({
  getParentRoute: () => daLayoutRoute,
  path: '/settlement',
  component: DaSettlementPage,
});

const daNominationsRoute = createRoute({
  getParentRoute: () => daLayoutRoute,
  path: '/nominations',
  component: DaNominationPage,
});

const daImbalanceRoute = createRoute({
  getParentRoute: () => daLayoutRoute,
  path: '/imbalance',
  component: DaImbalancePage,
});

const daFeesRoute = createRoute({
  getParentRoute: () => daLayoutRoute,
  path: '/fees',
  component: DaFeesPage,
});

const daAlertsRoute = createRoute({
  getParentRoute: () => daLayoutRoute,
  path: '/alerts',
  component: DaAlertsPage,
});

// Build the route tree
export const routeTree = rootRoute.addChildren([
  indexRoute,
  dashboardRoute,
  daLayoutRoute.addChildren([
    daIndexRoute,
    daImportRoute,
    daImportDetailRoute,
    daSettlementRoute,
    daNominationsRoute,
    daImbalanceRoute,
    daFeesRoute,
    daAlertsRoute,
  ]),
]);

// Create and export the router
export const router = createRouter({ routeTree });

// Type registration
declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router;
  }
}
