/**
 * Typed fetch wrapper with tenant injection.
 *
 * All API calls go through this client. It:
 * - Reads the base URL from VITE_API_BASE_URL env var (defaults to '' for proxy)
 * - Injects tenantId as a query parameter on every request
 * - Sets Accept: application/json
 * - Handles HTTP errors with typed responses
 */

const BASE_URL = import.meta.env.VITE_API_BASE_URL as string | undefined ?? '';

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly statusText: string,
    public readonly body: unknown,
  ) {
    super(`API Error ${status}: ${statusText}`);
    this.name = 'ApiError';
  }
}

export async function apiFetch<T>(
  path: string,
  params: Record<string, string | number | boolean | undefined>,
  tenantId: string,
): Promise<T> {
  const url = new URL(`${BASE_URL}${path}`, window.location.origin);

  // Always inject tenantId
  url.searchParams.set('tenantId', tenantId);

  // Add other params
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined) {
      url.searchParams.set(key, String(value));
    }
  }

  const response = await fetch(url.toString(), {
    method: 'GET',
    headers: {
      'Accept': 'application/json',
    },
  });

  if (!response.ok) {
    let body: unknown = null;
    try {
      body = await response.json();
    } catch {
      // Response body may not be JSON
    }
    throw new ApiError(response.status, response.statusText, body);
  }

  return response.json() as Promise<T>;
}
