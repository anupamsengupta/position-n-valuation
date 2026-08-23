import { useCallback, useEffect, useRef } from 'react';
import { Link } from '@tanstack/react-router';
import { ProgressStepper, type StepDefinition } from '@/components/primitives/ProgressStepper';
import { DaStatusBadge, type DaStatus } from './DaStatusBadge';
import { NumericCell } from '@/components/primitives/NumericCell';
import { SkeletonCard } from '@/components/primitives/SkeletonRow';
import { useDaImportDetail } from '@/hooks/useDaQueries';
import { TERMINAL_IMPORT_STATUSES } from '@/schemas/daApi';
import { parseNumericValue } from '@/lib/numberUtils';
import { cn } from '@/lib/cn';

export interface DaImportDetailPanelProps {
  sessionId: string;
  onClose: () => void;
}

const IMPORT_STEPS: StepDefinition[] = [
  { key: 'PENDING', label: 'Pending' },
  { key: 'VALIDATING', label: 'Validating' },
  { key: 'VALIDATED', label: 'Validated' },
  { key: 'IMPORTING', label: 'Importing' },
  { key: 'IMPORTED', label: 'Imported' },
];

const ERROR_STATES: ReadonlySet<string> = new Set([
  'VALIDATION_FAILED',
  'IMPORT_FAILED',
]);

/**
 * Detail panel for a selected import session. Shows progress stepper,
 * status badge, volume comparison, and validation errors.
 * Polls at 2s while the session is non-terminal via useDaImportDetail.
 */
export function DaImportDetailPanel({ sessionId, onClose }: DaImportDetailPanelProps) {
  const detailQuery = useDaImportDetail(sessionId);
  const panelRef = useRef<HTMLDivElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);

  // Focus the panel on mount for keyboard accessibility
  useEffect(() => {
    panelRef.current?.focus();
  }, [sessionId]);

  const handleClose = useCallback(() => {
    onClose();
  }, [onClose]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        handleClose();
      }
    },
    [handleClose],
  );

  if (detailQuery.isLoading) {
    return <SkeletonCard className="mt-4" />;
  }

  if (detailQuery.isError) {
    return (
      <div role="alert" className="p-4 text-sm text-numeric-negative mt-4">
        Failed to load session detail. {detailQuery.error?.message}
      </div>
    );
  }

  const data = detailQuery.data;
  if (!data) {
    return null;
  }

  const exchangeVol = parseNumericValue(data.exchangeReportedTotalMwh);
  const importedVol = parseNumericValue(data.importedTotalMwh);
  const volumeMatch =
    exchangeVol !== null &&
    importedVol !== null &&
    Math.abs(exchangeVol - importedVol) < 0.0001;

  // Map error statuses to a step that the stepper understands
  const stepperStatus = ERROR_STATES.has(data.status)
    ? data.status === 'VALIDATION_FAILED'
      ? 'VALIDATING'
      : 'IMPORTING'
    : data.status;

  return (
    <div
      ref={panelRef}
      role="region"
      aria-label="Import session detail"
      tabIndex={-1}
      onKeyDown={handleKeyDown}
      className={cn(
        'mt-4 rounded-lg border border-border-default bg-bg-secondary p-4 space-y-4',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus',
      )}
    >
      {/* Header row */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <h3 className="text-sm font-semibold text-text-primary">
            Session: {data.sessionId.slice(0, 8)}...
          </h3>
          <DaStatusBadge status={data.status as DaStatus} size="md" />
        </div>
        <button
          ref={closeButtonRef}
          type="button"
          onClick={handleClose}
          aria-label="Close detail panel"
          className={cn(
            'px-2 py-1 text-xs rounded',
            'border border-border-default text-text-secondary',
            'hover:bg-bg-tertiary transition-colors',
            'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus',
          )}
        >
          Close
        </button>
      </div>

      {/* Progress stepper */}
      <ProgressStepper
        steps={IMPORT_STEPS}
        currentStep={stepperStatus}
        terminalStates={TERMINAL_IMPORT_STATUSES}
        errorStates={ERROR_STATES}
      />

      {/* Metadata */}
      <div className="grid grid-cols-2 gap-x-6 gap-y-2 text-xs">
        <div>
          <span className="text-text-muted">Delivery Day</span>
          <p className="font-medium text-text-primary">{data.deliveryDay}</p>
        </div>
        <div>
          <span className="text-text-muted">Zone</span>
          <p className="font-medium text-text-primary">{data.biddingZone}</p>
        </div>
        <div>
          <span className="text-text-muted">Exchange Reported MWh</span>
          <p className="font-medium">
            <NumericCell value={data.exchangeReportedTotalMwh} precision="MWH" />
          </p>
        </div>
        <div>
          <span className="text-text-muted">Imported MWh</span>
          <p className="font-medium">
            <NumericCell value={data.importedTotalMwh} precision="MWH" />
          </p>
        </div>
      </div>

      {/* Volume match indicator */}
      {exchangeVol !== null && importedVol !== null && (
        <div className="flex items-center gap-2 text-xs">
          {volumeMatch ? (
            <>
              <span className="text-status-settled" aria-hidden="true">{'\u2713'}</span>
              <span className="text-status-settled font-medium">
                Volume match confirmed
              </span>
            </>
          ) : (
            <>
              <span className="text-numeric-negative" aria-hidden="true">{'\u2715'}</span>
              <span className="text-numeric-negative font-medium">
                Volume mismatch detected
              </span>
            </>
          )}
        </div>
      )}

      {/* Validation errors */}
      {data.validationErrors && data.validationErrors.length > 0 && (
        <div className="space-y-1">
          <p className="text-xs font-semibold text-numeric-negative">
            Validation Errors
          </p>
          <ul className="list-disc list-inside text-xs text-numeric-negative space-y-0.5">
            {data.validationErrors.map((err, idx) => (
              <li key={idx}>{err}</li>
            ))}
          </ul>
        </div>
      )}

      {/* View Trades link */}
      {TERMINAL_IMPORT_STATUSES.has(data.status) &&
        data.status === 'IMPORTED' && (
          <Link
            to="/da/settlement"
            search={{ deliveryDay: data.deliveryDay, zone: data.biddingZone }}
            className={cn(
              'inline-block text-xs font-medium text-interactive-focus underline',
              'hover:text-interactive-focus/80 transition-colors',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus rounded',
            )}
          >
            View Trades in Settlement
          </Link>
        )}
    </div>
  );
}
