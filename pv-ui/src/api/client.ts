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
  params: Record<string, string | number | boolean | string[] | undefined>,
  tenantId: string,
): Promise<T> {
  const url = new URL(`${BASE_URL}${path}`, window.location.origin);

  // Always inject tenantId
  url.searchParams.set('tenantId', tenantId);

  // Add other params (arrays produce repeated params, e.g. ?positionId=a&positionId=b)
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined) continue;
    if (Array.isArray(value)) {
      for (const v of value) {
        url.searchParams.append(key, v);
      }
    } else {
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

/**
 * Typed fetch wrapper for POST/PUT/DELETE mutations with tenant injection.
 *
 * Body is serialized as JSON. For multipart uploads, use `apiFetchMultipart`.
 */
export async function apiFetchMutation<T>(
  path: string,
  method: 'POST' | 'PUT' | 'DELETE',
  body: unknown | null,
  tenantId: string,
): Promise<T> {
  const url = new URL(`${BASE_URL}${path}`, window.location.origin);
  url.searchParams.set('tenantId', tenantId);

  const response = await fetch(url.toString(), {
    method,
    headers: {
      'Accept': 'application/json',
      ...(body !== null ? { 'Content-Type': 'application/json' } : {}),
    },
    ...(body !== null ? { body: JSON.stringify(body) } : {}),
  });

  if (!response.ok) {
    let errorBody: unknown = null;
    try {
      errorBody = await response.json();
    } catch {
      // Response body may not be JSON
    }
    throw new ApiError(response.status, response.statusText, errorBody);
  }

  return response.json() as Promise<T>;
}

/**
 * Typed fetch wrapper for multipart/form-data uploads with tenant injection.
 *
 * Do NOT set Content-Type manually — the browser sets it with the boundary.
 */
export async function apiFetchMultipart<T>(
  path: string,
  formData: FormData,
  tenantId: string,
): Promise<T> {
  const url = new URL(`${BASE_URL}${path}`, window.location.origin);
  url.searchParams.set('tenantId', tenantId);

  const response = await fetch(url.toString(), {
    method: 'POST',
    headers: {
      'Accept': 'application/json',
    },
    body: formData,
  });

  if (!response.ok) {
    let errorBody: unknown = null;
    try {
      errorBody = await response.json();
    } catch {
      // Response body may not be JSON
    }
    throw new ApiError(response.status, response.statusText, errorBody);
  }

  return response.json() as Promise<T>;
}
