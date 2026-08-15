import { useQuery } from '@tanstack/react-query';
import { dashboardKeys } from '@/api/queryKeys';
import { fetchPortfolios } from '@/api/portfolios';
import { useTenantStore } from '@/hooks/useTenantStore';

/**
 * Fetches distinct portfolio IDs from the backend.
 * Portfolios change infrequently, so staleTime is generous (5 min).
 */
export function usePortfolios() {
  const tenantId = useTenantStore((s) => s.tenantId);
  return useQuery({
    queryKey: dashboardKeys.portfolios(tenantId),
    queryFn: () => fetchPortfolios(tenantId),
    enabled: !!tenantId,
    staleTime: 300_000,
    gcTime: 600_000,
  });
}
