import { z } from 'zod';

/**
 * BigDecimal values from the backend may serialize as JSON numbers (Jackson default)
 * or as strings. We accept both and coerce to string for uniform handling.
 * The UI then uses Number() for display formatting via Intl.NumberFormat.
 */
const bigDecimalValue = z.union([
  z.string().refine((val) => !isNaN(Number(val)), {
    message: 'Must be a valid decimal string',
  }),
  z.number().transform((val) => String(val)),
]);

const bigDecimalNullable = z.union([
  z.string().refine((val) => !isNaN(Number(val)), {
    message: 'Must be a valid decimal string',
  }),
  z.number().transform((val) => String(val)),
  z.null(),
]);

const instantString = z.string();

// ---------------------------------------------------------------------------
// L1: Portfolio Summary
// ---------------------------------------------------------------------------

export const portfolioSummarySchema = z.object({
  portfolioId: z.string(),
  currency: z.string(),
  realizedPnl: bigDecimalValue,
  unrealizedMtm: bigDecimalValue,
  totalPortfolioValue: bigDecimalValue,
  settledNetMw: bigDecimalValue,
  settledNetMwh: bigDecimalValue,
  forwardNetMw: bigDecimalValue,
  forwardNetMwh: bigDecimalValue,
  dataAsOf: instantString,
});

// ---------------------------------------------------------------------------
// L2: Rollup Grid
// ---------------------------------------------------------------------------

export const rollupCellSchema = z.object({
  periodStart: instantString,
  periodEnd: instantString,
  granularity: z.string(),
  deliveryPointId: z.string(),
  portfolioId: z.string(),
  isPeak: z.boolean(),
  netMw: bigDecimalValue,
  netMwh: bigDecimalValue,
  price: bigDecimalNullable,
  marketPrice: bigDecimalNullable,
  settledValue: bigDecimalValue,
  marketValue: bigDecimalValue,
  pnl: bigDecimalValue,
  forwardMarkValue: bigDecimalValue,
  currency: z.string(),
  calendarVersion: z.string().nullable(),
  versionHash: z.string().nullable(),
});

// ---------------------------------------------------------------------------
// L3: Position Contributions
// ---------------------------------------------------------------------------

export const positionContributionSchema = z.object({
  positionId: z.string().nullable(),
  tradeId: z.string(),
  tradeLegId: z.string(),
  tradeVersion: z.number().int(),
  deliveryStart: instantString,
  deliveryEnd: instantString,
  quantity: bigDecimalValue,
  volumeUnit: z.string(),
  deliveryPointId: z.string(),
  deliveryStatus: z.string(),
  settledMw: bigDecimalNullable,
  settledMwh: bigDecimalNullable,
  avgPrice: bigDecimalNullable,
  settledValue: bigDecimalNullable,
  marketValue: bigDecimalNullable,
  realizedPnl: bigDecimalNullable,
  forwardMw: bigDecimalNullable,
  forwardMwh: bigDecimalNullable,
  forwardMarkValue: bigDecimalNullable,
  unrealizedMtm: bigDecimalNullable,
  currency: z.string(),
});

// ---------------------------------------------------------------------------
// L4: Daily Aggregates
// ---------------------------------------------------------------------------

export const dailyAggregateSchema = z.object({
  dayStart: instantString,
  dayEnd: instantString,
  dayStatus: z.string(),
  intervalCount: z.number().int(),
  settledMw: bigDecimalNullable,
  settledMwh: bigDecimalNullable,
  avgPrice: bigDecimalNullable,
  settledValue: bigDecimalNullable,
  marketValue: bigDecimalNullable,
  realizedPnl: bigDecimalNullable,
  forwardMw: bigDecimalNullable,
  forwardMwh: bigDecimalNullable,
  curvePrice: bigDecimalNullable,
  forwardMarkValue: bigDecimalNullable,
  currency: z.string(),
});

// ---------------------------------------------------------------------------
// L4: Settlement Cells
// ---------------------------------------------------------------------------

export const settlementCellSchema = z.object({
  cellId: z.string(),
  tenantId: z.string(),
  positionId: z.string(),
  intervalStart: instantString,
  intervalEnd: instantString,
  valuationType: z.string().nullable().optional(),
  cellStatus: z.string().nullable().optional(),
  price: bigDecimalValue,
  volumeMw: bigDecimalValue,
  volumeMwh: bigDecimalValue,
  amount: bigDecimalValue,
  marketPrice: bigDecimalValue,
  marketAmount: bigDecimalValue,
  pnl: bigDecimalValue,
  currency: z.string(),
  activeLeaves: z.array(z.string()).nullable().optional(),
  computedAt: instantString,
});

// ---------------------------------------------------------------------------
// L4: Forward Interval Detail
// ---------------------------------------------------------------------------

export const forwardIntervalDetailSchema = z.object({
  intervalStart: instantString,
  intervalEnd: instantString,
  positionId: z.string().nullable(),
  tradeLegId: z.string().nullable(),
  resolvedQty: bigDecimalValue,
  resolvedEnergy: bigDecimalValue,
  multiplier: bigDecimalValue,
  seriesKey: z.string(),
  evaluatedPrice: bigDecimalNullable,
  markValue: bigDecimalNullable,
  curveId: z.string().nullable(),
  curveVersion: z.number().int().nullable(),
  currency: z.string().nullable(),
  markType: z.string().optional(),
});

// ---------------------------------------------------------------------------
// API Response wrapper
// ---------------------------------------------------------------------------

export function apiResponseSchema<T extends z.ZodType>(dataSchema: T) {
  return z.object({
    data: dataSchema,
    message: z.string().optional(),
    timestamp: z.string().optional(),
  });
}

// ---------------------------------------------------------------------------
// Inferred TypeScript types
// ---------------------------------------------------------------------------

export type PortfolioSummaryDto = z.infer<typeof portfolioSummarySchema>;
export type RollupCellDto = z.infer<typeof rollupCellSchema>;
export type PositionContributionDto = z.infer<typeof positionContributionSchema>;
export type DailyAggregateDto = z.infer<typeof dailyAggregateSchema>;
export type SettlementCellDto = z.infer<typeof settlementCellSchema>;
export type ForwardIntervalDetailDto = z.infer<typeof forwardIntervalDetailSchema>;
