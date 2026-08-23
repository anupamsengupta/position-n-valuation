/**
 * Mutation hooks for Day-Ahead Exchange Spot actions.
 * Each mutation invalidates relevant query keys on success.
 */

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { daKeys } from '@/api/daQueryKeys';
import {
  triggerDaImport,
  uploadDaImportFile,
  acknowledgeDaAlert,
  resolveDaAlert,
} from '@/api/daApi';
import { useTenantStore } from './useTenantStore';

// ---------------------------------------------------------------------------
// Import Trigger (JSON body)
// ---------------------------------------------------------------------------

export function useDaImportTrigger() {
  const tenantId = useTenantStore((s) => s.tenantId);
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (body: unknown) => triggerDaImport(tenantId, body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: daKeys.all });
    },
  });
}

// ---------------------------------------------------------------------------
// File Upload (multipart)
// ---------------------------------------------------------------------------

export function useDaFileUpload() {
  const tenantId = useTenantStore((s) => s.tenantId);
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (formData: FormData) => uploadDaImportFile(tenantId, formData),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: daKeys.all });
    },
  });
}

// ---------------------------------------------------------------------------
// Alert Acknowledge
// ---------------------------------------------------------------------------

export function useDaAlertAcknowledge() {
  const tenantId = useTenantStore((s) => s.tenantId);
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (alertId: string) => acknowledgeDaAlert(tenantId, alertId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: daKeys.all });
    },
  });
}

// ---------------------------------------------------------------------------
// Alert Resolve
// ---------------------------------------------------------------------------

export function useDaAlertResolve() {
  const tenantId = useTenantStore((s) => s.tenantId);
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (alertId: string) => resolveDaAlert(tenantId, alertId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: daKeys.all });
    },
  });
}
