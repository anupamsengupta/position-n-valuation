import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';
import { useTenantStore } from './useTenantStore';
import {
  useDaImportTrigger,
  useDaFileUpload,
  useDaAlertAcknowledge,
  useDaAlertResolve,
} from './useDaMutations';
import { ApiError } from '@/api/client';

// Mock the API functions
const mockTriggerDaImport = vi.fn();
const mockUploadDaImportFile = vi.fn();
const mockAcknowledgeDaAlert = vi.fn();
const mockResolveDaAlert = vi.fn();

vi.mock('@/api/daApi', () => ({
  triggerDaImport: (...args: unknown[]) => mockTriggerDaImport(...args),
  uploadDaImportFile: (...args: unknown[]) => mockUploadDaImportFile(...args),
  acknowledgeDaAlert: (...args: unknown[]) => mockAcknowledgeDaAlert(...args),
  resolveDaAlert: (...args: unknown[]) => mockResolveDaAlert(...args),
}));

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  return {
    wrapper: function Wrapper({ children }: { children: React.ReactNode }) {
      return createElement(QueryClientProvider, { client: queryClient }, children);
    },
    queryClient,
  };
}

describe('DA Mutation Hooks', () => {
  beforeEach(() => {
    useTenantStore.getState().setTenant('TN_0042', 'Test Tenant');
    vi.clearAllMocks();
  });

  describe('useDaImportTrigger', () => {
    it('calls triggerDaImport with tenant and body', async () => {
      const mockSession = { sessionId: 'sess-001', status: 'PENDING' };
      mockTriggerDaImport.mockResolvedValueOnce(mockSession);

      const { wrapper, queryClient } = createWrapper();
      const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

      const { result } = renderHook(() => useDaImportTrigger(), { wrapper });

      await act(async () => {
        result.current.mutate({ exchange: 'EPEX_SPOT', results: [] });
      });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockTriggerDaImport).toHaveBeenCalledWith('TN_0042', { exchange: 'EPEX_SPOT', results: [] });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['da'] });
    });

    it('propagates ApiError on failure', async () => {
      mockTriggerDaImport.mockRejectedValueOnce(new ApiError(400, 'Bad Request', { message: 'Invalid' }));

      const { wrapper } = createWrapper();
      const { result } = renderHook(() => useDaImportTrigger(), { wrapper });

      await act(async () => {
        result.current.mutate({});
      });

      await waitFor(() => expect(result.current.isError).toBe(true));
      expect(result.current.error).toBeInstanceOf(ApiError);
    });
  });

  describe('useDaFileUpload', () => {
    it('calls uploadDaImportFile with FormData', async () => {
      const mockSession = { sessionId: 'sess-002', status: 'VALIDATING' };
      mockUploadDaImportFile.mockResolvedValueOnce(mockSession);

      const { wrapper, queryClient } = createWrapper();
      const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

      const { result } = renderHook(() => useDaFileUpload(), { wrapper });

      const formData = new FormData();
      await act(async () => {
        result.current.mutate(formData);
      });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockUploadDaImportFile).toHaveBeenCalledWith('TN_0042', formData);
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['da'] });
    });
  });

  describe('useDaAlertAcknowledge', () => {
    it('calls acknowledgeDaAlert and invalidates queries on success', async () => {
      const mockAlert = { alertId: 'alert-001', status: 'ACKNOWLEDGED' };
      mockAcknowledgeDaAlert.mockResolvedValueOnce(mockAlert);

      const { wrapper, queryClient } = createWrapper();
      const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

      const { result } = renderHook(() => useDaAlertAcknowledge(), { wrapper });

      await act(async () => {
        result.current.mutate('alert-001');
      });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockAcknowledgeDaAlert).toHaveBeenCalledWith('TN_0042', 'alert-001');
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['da'] });
    });
  });

  describe('useDaAlertResolve', () => {
    it('calls resolveDaAlert and invalidates queries on success', async () => {
      const mockAlert = { alertId: 'alert-001', status: 'RESOLVED' };
      mockResolveDaAlert.mockResolvedValueOnce(mockAlert);

      const { wrapper, queryClient } = createWrapper();
      const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

      const { result } = renderHook(() => useDaAlertResolve(), { wrapper });

      await act(async () => {
        result.current.mutate('alert-001');
      });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockResolveDaAlert).toHaveBeenCalledWith('TN_0042', 'alert-001');
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['da'] });
    });

    it('propagates error on failure', async () => {
      mockResolveDaAlert.mockRejectedValueOnce(new ApiError(404, 'Not Found', null));

      const { wrapper } = createWrapper();
      const { result } = renderHook(() => useDaAlertResolve(), { wrapper });

      await act(async () => {
        result.current.mutate('nonexistent');
      });

      await waitFor(() => expect(result.current.isError).toBe(true));
      expect(result.current.error).toBeInstanceOf(ApiError);
    });
  });
});
