/**
 * Static portfolio list — fallback until backend endpoint exists.
 */
export const KNOWN_PORTFOLIOS = [
  { portfolioId: 'WIND_DE', label: 'Wind DE/LU' },
  { portfolioId: 'SOLAR_DE', label: 'Solar DE' },
  { portfolioId: 'GAS_NL', label: 'Gas NL' },
] as const;

export type KnownPortfolio = (typeof KNOWN_PORTFOLIOS)[number];
