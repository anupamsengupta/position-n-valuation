import { useCallback, useEffect } from 'react';
import { DaFileUploadArea } from './DaFileUploadArea';
import { DaImportHistoryTable } from './DaImportHistoryTable';
import { DaImportDetailPanel } from './DaImportDetailPanel';
import { ErrorBoundary } from '@/components/primitives/ErrorBoundary';
import { useDaImportHistory } from '@/hooks/useDaQueries';
import { useDaFilters } from '@/hooks/useDaFilters';
import { useDaSelection } from '@/hooks/useDaSelection';

export interface DaImportPageProps {
  /** Route param: pre-selected session ID from /da/import/$sessionId */
  sessionId?: string;
}

/**
 * DA Import page. Orchestrates file upload, import history table,
 * and import detail panel.
 */
export function DaImportPage({ sessionId: routeSessionId }: DaImportPageProps) {
  const selectedSessionId = useDaSelection((s) => s.selectedSessionId);
  const setSelectedSession = useDaSelection((s) => s.setSelectedSession);
  const importFilters = useDaFilters((s) => s.getImportFilters)();
  const historyQuery = useDaImportHistory(importFilters);

  // Sync route param into selection store on mount
  useEffect(() => {
    if (routeSessionId && routeSessionId !== selectedSessionId) {
      setSelectedSession(routeSessionId);
    }
  }, [routeSessionId, selectedSessionId, setSelectedSession]);

  const handleUploadSuccess = useCallback(
    (newSessionId: string) => {
      setSelectedSession(newSessionId);
    },
    [setSelectedSession],
  );

  const handleSessionSelect = useCallback(
    (id: string) => {
      setSelectedSession(id);
    },
    [setSelectedSession],
  );

  const handleDetailClose = useCallback(() => {
    setSelectedSession(null);
  }, [setSelectedSession]);

  const effectiveSessionId = selectedSessionId ?? routeSessionId ?? null;

  return (
    <div className="space-y-6">
      <h2 className="text-lg font-semibold text-text-primary">
        Auction Import
      </h2>

      {/* File upload area */}
      <ErrorBoundary>
        <DaFileUploadArea onUploadSuccess={handleUploadSuccess} />
      </ErrorBoundary>

      {/* Import history table */}
      <ErrorBoundary>
        <section aria-label="Import history">
          <h3 className="text-sm font-semibold text-text-secondary mb-2">
            Import History
          </h3>
          <DaImportHistoryTable
            data={historyQuery.data}
            isLoading={historyQuery.isLoading}
            selectedSessionId={effectiveSessionId}
            onSessionSelect={handleSessionSelect}
          />
        </section>
      </ErrorBoundary>

      {/* Conditional detail panel */}
      {effectiveSessionId !== null && (
        <ErrorBoundary>
          <DaImportDetailPanel
            sessionId={effectiveSessionId}
            onClose={handleDetailClose}
          />
        </ErrorBoundary>
      )}
    </div>
  );
}
