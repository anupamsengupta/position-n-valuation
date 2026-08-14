import {
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
  redirect,
} from '@tanstack/react-router';
import { AppShell } from '@/components/layout/AppShell';
import { DashboardPage } from '@/components/dashboard/DashboardPage';

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

// Build the route tree
export const routeTree = rootRoute.addChildren([indexRoute, dashboardRoute]);

// Create and export the router
export const router = createRouter({ routeTree });

// Type registration
declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router;
  }
}
