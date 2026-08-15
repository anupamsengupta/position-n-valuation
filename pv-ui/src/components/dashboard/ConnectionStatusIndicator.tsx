import type { ConnectionStatus } from '@/hooks/useRealtimeInvalidation';

interface ConnectionStatusIndicatorProps {
  status: ConnectionStatus;
}

const STATUS_CONFIG: Record<ConnectionStatus, { color: string; pulse: boolean; label: string }> = {
  connected: { color: 'bg-green-500', pulse: false, label: 'Live' },
  connecting: { color: 'bg-yellow-500', pulse: true, label: 'Connecting...' },
  disconnected: { color: 'bg-red-500', pulse: false, label: 'Offline' },
};

/**
 * Small dot + label indicating the SSE connection status.
 */
export function ConnectionStatusIndicator({ status }: ConnectionStatusIndicatorProps) {
  const config = STATUS_CONFIG[status];

  return (
    <div className="flex items-center gap-1.5" aria-label={`Connection status: ${config.label}`}>
      <span
        className={`inline-block w-2 h-2 rounded-full ${config.color} ${config.pulse ? 'animate-pulse' : ''}`}
      />
      <span className="text-xs text-text-muted">{config.label}</span>
    </div>
  );
}
