import { useEffect, useRef, useCallback, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useTenantStore } from './useTenantStore';

export type ConnectionStatus = 'connected' | 'connecting' | 'disconnected';

type ChangeType =
  | 'SETTLEMENT_COMPUTED'
  | 'ROLLUP_MATERIALIZED'
  | 'MARKET_DATA_REVALUED'
  | 'POSITION_CAPTURED'
  | 'VOLUME_REVALUED';

interface DashboardUpdateEvent {
  changeType: ChangeType;
  portfolioId: string | null;
  eventTime: string;
}

/**
 * Map of change types to the query key segments that should be invalidated.
 * These correspond to the second element in dashboardKeys factory functions.
 */
const INVALIDATION_MAP: Record<ChangeType, string[]> = {
  SETTLEMENT_COMPUTED: ['summary', 'rollups', 'positions', 'daily', 'settled-day'],
  ROLLUP_MATERIALIZED: ['summary', 'rollups'],
  MARKET_DATA_REVALUED: ['summary', 'rollups', 'forward-day'],
  POSITION_CAPTURED: ['portfolios', 'summary', 'rollups', 'positions'],
  VOLUME_REVALUED: ['summary', 'rollups', 'positions', 'daily', 'settled-day'],
};

/**
 * Opens an SSE connection to /api/dashboard/events and invalidates
 * React Query caches when the backend pushes dashboard-update events.
 *
 * Returns the current connection status for UI display.
 */
export function useRealtimeInvalidation(): ConnectionStatus {
  const tenantId = useTenantStore((s) => s.tenantId);
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<ConnectionStatus>('disconnected');
  const eventSourceRef = useRef<EventSource | null>(null);

  const invalidateForChange = useCallback(
    (changeType: ChangeType) => {
      const prefixes = INVALIDATION_MAP[changeType];
      if (!prefixes) return;

      for (const prefix of prefixes) {
        queryClient.invalidateQueries({
          queryKey: ['dashboard', prefix],
        });
      }
    },
    [queryClient],
  );

  useEffect(() => {
    if (!tenantId) {
      setStatus('disconnected');
      return;
    }

    setStatus('connecting');

    const es = new EventSource(`/api/dashboard/events?tenantId=${encodeURIComponent(tenantId)}`);
    eventSourceRef.current = es;

    es.addEventListener('connected', () => {
      setStatus('connected');
    });

    es.addEventListener('dashboard-update', (event) => {
      try {
        const data: DashboardUpdateEvent = JSON.parse(event.data);
        invalidateForChange(data.changeType);
      } catch {
        // Ignore malformed events
      }
    });

    es.onopen = () => {
      setStatus('connected');
    };

    es.onerror = () => {
      // EventSource auto-reconnects; mark as connecting while it retries
      setStatus('connecting');
    };

    return () => {
      es.close();
      eventSourceRef.current = null;
      setStatus('disconnected');
    };
  }, [tenantId, invalidateForChange]);

  return status;
}
