import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from '@tanstack/react-router';
import { router } from './routeTree';
import { useTenantStore } from './hooks/useTenantStore';
import { useUserPreferences } from './hooks/useUserPreferences';
import './styles/globals.css';

// Create the query client with sensible defaults
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 2,
      refetchOnWindowFocus: true,
      staleTime: 30_000,
      gcTime: 300_000,
    },
  },
});

/**
 * App initialization: set tenant from env var and apply theme.
 */
function initializeApp() {
  // Set tenant from env var (simulator convenience).
  // In production, tenant comes from auth context, not env var.
  // For the simulator, set VITE_DEFAULT_TENANT_ID in .env.local.
  const defaultTenantId = import.meta.env.VITE_DEFAULT_TENANT_ID as string | undefined;
  const defaultTenantName = import.meta.env.VITE_DEFAULT_TENANT_NAME as string | undefined;
  if (!defaultTenantId) {
    console.error(
      '[pv-ui] VITE_DEFAULT_TENANT_ID is not set. ' +
      'Set this environment variable in .env.local or configure tenant resolution from auth context.',
    );
  }
  const store = useTenantStore.getState();
  if (!store.tenantId && defaultTenantId) {
    store.setTenant(defaultTenantId, defaultTenantName ?? defaultTenantId);
  }

  // Apply saved theme
  const theme = useUserPreferences.getState().theme;
  document.documentElement.setAttribute('data-theme', theme);
}

initializeApp();

// Mount the app
const rootElement = document.getElementById('root');
if (!rootElement) {
  throw new Error('Root element not found. Ensure index.html has a <div id="root"></div>.');
}

createRoot(rootElement).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
);
