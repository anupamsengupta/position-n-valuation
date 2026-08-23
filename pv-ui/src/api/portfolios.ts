import { z } from 'zod';
import { apiFetch } from './client';
import { apiResponseSchema } from '@/schemas/api';

/**
 * Static portfolio list — fallback until backend endpoint exists.
 */
export const KNOWN_PORTFOLIOS = [
  { portfolioId: 'WIND_DE', label: 'Wind DE/LU' },
  { portfolioId: 'SOLAR_DE', label: 'Solar DE' },
  { portfolioId: 'GAS_NL', label: 'Gas NL' },
] as const;

export type KnownPortfolio = (typeof KNOWN_PORTFOLIOS)[number];

/**
 * Fetch distinct portfolio IDs from current-knowledge ACTIVE positions.
 * GET /api/dashboard/portfolios?tenantId=...
 */
export async function fetchPortfolios(tenantId: string): Promise<string[]> {
  const raw = await apiFetch<unknown>('/api/dashboard/portfolios', {}, tenantId);
  const schema = apiResponseSchema(z.array(z.string()));
  const parsed = schema.parse(raw);
  return parsed.data;
}
