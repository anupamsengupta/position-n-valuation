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
// Import Session Status
// ---------------------------------------------------------------------------

export const importSessionStatusEnum = z.enum([
  'PENDING',
  'VALIDATING',
  'VALIDATED',
  'IMPORTING',
  'IMPORTED',
  'VALIDATION_FAILED',
  'IMPORT_FAILED',
]);

export type ImportSessionStatus = z.infer<typeof importSessionStatusEnum>;

export const TERMINAL_IMPORT_STATUSES: ReadonlySet<ImportSessionStatus> = new Set([
  'IMPORTED',
  'VALIDATION_FAILED',
  'IMPORT_FAILED',
]);

// ---------------------------------------------------------------------------
// 1. Auction Import Session
// ---------------------------------------------------------------------------

export const auctionImportSessionSchema = z.object({
  sessionId: z.string(),
  tenantId: z.string(),
  exchange: z.string(),
  biddingZone: z.string(),
  deliveryDay: z.string(), // ISO local date YYYY-MM-DD
  importTimestamp: instantString,
  status: importSessionStatusEnum,
  exchangeReportedTotalMwh: bigDecimalNullable,
  importedTotalMwh: bigDecimalNullable,
  intervalCount: z.number().int().nullable(),
  fileReference: z.string().nullable(),
  tradeIds: z.array(z.string()).nullable(),
  validationErrors: z.array(z.string()).nullable(),
  createdAt: instantString,
  completedAt: instantString.nullable(),
});

// ---------------------------------------------------------------------------
// 2. DA Settlement Row
// ---------------------------------------------------------------------------

export const daSettlementRowSchema = z.object({
  intervalStart: instantString,
  intervalEnd: instantString,
  tradeId: z.string(),
  tradeLegId: z.string(),
  direction: z.enum(['BUY', 'SELL']),
  price: bigDecimalValue,
  volumeMw: bigDecimalValue,
  energyMwh: bigDecimalValue,
  amount: bigDecimalValue,
  cellStatus: z.string(),
  currency: z.string(),
});

// ---------------------------------------------------------------------------
// 3. DA Settlement Grid (wraps rows + summary)
// ---------------------------------------------------------------------------

export const daSettlementGridSchema = z.object({
  deliveryDay: z.string(),
  biddingZone: z.string(),
  intervalCount: z.number().int(),
  rows: z.array(daSettlementRowSchema),
  summary: z.object({
    totalEnergyMwh: bigDecimalValue,
    totalSettlement: bigDecimalValue,
    vwap: bigDecimalValue,
    currency: z.string(),
  }),
});

// ---------------------------------------------------------------------------
// 4. Nomination Comparison Row
// ---------------------------------------------------------------------------

export const nominationComparisonRowSchema = z.object({
  intervalStart: instantString,
  intervalEnd: instantString,
  tradedMw: bigDecimalNullable,
  nominatedMw: bigDecimalNullable,
  deviationMw: bigDecimalNullable,
  status: z.enum(['OK', 'DEVIATION', 'MISSING']),
});

// ---------------------------------------------------------------------------
// 5. Nomination Grid Response
// ---------------------------------------------------------------------------

export const daNominationGridSchema = z.object({
  deliveryDay: z.string(),
  balancingGroupId: z.string().nullable(),
  intervalCount: z.number().int(),
  rows: z.array(nominationComparisonRowSchema),
  summary: z.object({
    totalTradedMwh: bigDecimalValue,
    totalNominatedMwh: bigDecimalNullable,
    netDeviationMwh: bigDecimalNullable,
    intervalsWithDeviation: z.number().int(),
    totalIntervals: z.number().int(),
  }),
  nominationSubmitted: z.boolean(),
  gateClosureStatus: z.enum(['OK', 'WARNING', 'CRITICAL']).nullable(),
});

// ---------------------------------------------------------------------------
// 6. Imbalance Record Row
// ---------------------------------------------------------------------------

export const imbalanceRowSchema = z.object({
  intervalStart: instantString,
  intervalEnd: instantString,
  nominatedMw: bigDecimalValue,
  actualMw: bigDecimalValue,
  imbalanceMw: bigDecimalValue,
  rebapPrice: bigDecimalValue,
  amount: bigDecimalValue,
  currency: z.string(),
});

// ---------------------------------------------------------------------------
// 7. Daily Imbalance Response
// ---------------------------------------------------------------------------

export const daImbalanceDailySchema = z.object({
  deliveryDay: z.string(),
  balancingGroupId: z.string(),
  recordVersion: z.number().int(),
  rows: z.array(imbalanceRowSchema),
  summary: z.object({
    netImbalanceMwh: bigDecimalValue,
    netImbalanceCost: bigDecimalValue,
    maxIntervalImbalanceMw: bigDecimalValue,
    maxIntervalTime: instantString,
    currency: z.string(),
  }),
  hasPriorVersion: z.boolean(),
  correctionDate: instantString.nullable(),
});

// ---------------------------------------------------------------------------
// 8. Monthly Imbalance Day Summary
// ---------------------------------------------------------------------------

export const imbalanceDaySummarySchema = z.object({
  deliveryDay: z.string(),
  netImbalanceMwh: bigDecimalValue,
  netCost: bigDecimalValue,
  maxDeviationMw: bigDecimalValue,
  intervalsWithImbalance: z.number().int(),
  totalIntervals: z.number().int(),
  currency: z.string(),
});

// ---------------------------------------------------------------------------
// 9. Monthly Imbalance Response
// ---------------------------------------------------------------------------

export const daImbalanceMonthlySchema = z.object({
  yearMonth: z.string(),
  balancingGroupId: z.string(),
  dailySummaries: z.array(imbalanceDaySummarySchema),
  monthlySummary: z.object({
    totalNetImbalanceMwh: bigDecimalValue,
    totalNetCost: bigDecimalValue,
    currency: z.string(),
  }),
});

// ---------------------------------------------------------------------------
// 10. Fee Line Item
// ---------------------------------------------------------------------------

export const feeLineItemSchema = z.object({
  feeType: z.string(),
  ratePerMwh: bigDecimalValue,
  grossVolumeMwh: bigDecimalValue,
  feeAmount: bigDecimalValue,
  currency: z.string(),
});

// ---------------------------------------------------------------------------
// 11. Fee Breakdown
// ---------------------------------------------------------------------------

export const daFeeBreakdownSchema = z.object({
  deliveryDay: z.string(),
  feeScheduleEffectiveDate: z.string(),
  memberTier: z.string().nullable(),
  items: z.array(feeLineItemSchema),
  totalFeeAmount: bigDecimalValue,
  grossVolumeMwh: bigDecimalValue,
  currency: z.string(),
});

// ---------------------------------------------------------------------------
// 12. Operational Alert
// ---------------------------------------------------------------------------

export const alertCategoryEnum = z.enum([
  'AUCTION_INGESTION',
  'DA_CLEARING_PRICES',
  'NOMINATION_SCHEDULING',
  'IMBALANCE_SETTLEMENT',
  'EXCHANGE_FEES',
  'SETTLEMENT',
]);

export type AlertCategory = z.infer<typeof alertCategoryEnum>;

export const alertSeverityEnum = z.enum(['CRITICAL', 'WARNING', 'INFO']);

export type AlertSeverity = z.infer<typeof alertSeverityEnum>;

export const alertStatusEnum = z.enum(['OPEN', 'ACKNOWLEDGED', 'RESOLVED']);

export type AlertStatus = z.infer<typeof alertStatusEnum>;

export const operationalAlertSchema = z.object({
  alertId: z.string(),
  tenantId: z.string(),
  category: alertCategoryEnum,
  severity: alertSeverityEnum,
  alertType: z.string(),
  message: z.string(),
  deliveryDay: z.string().nullable(),
  biddingZone: z.string().nullable(),
  sourceEventId: z.string().nullable(),
  raisedAt: instantString,
  acknowledgedBy: z.string().nullable(),
  acknowledgedAt: instantString.nullable(),
  resolvedAt: instantString.nullable(),
  status: alertStatusEnum,
});

// ---------------------------------------------------------------------------
// 13. KPI Summary
// ---------------------------------------------------------------------------

export const daKpiSchema = z.object({
  netVolumeMwh: bigDecimalValue,
  vwap: bigDecimalValue,
  settlementTotal: bigDecimalValue,
  exchangeFees: bigDecimalValue,
  imbalanceCost: bigDecimalNullable,
  openAlertCount: z.number().int(),
  maxAlertSeverity: alertSeverityEnum.nullable(),
  currency: z.string(),
});

// ---------------------------------------------------------------------------
// 14. Balancing Group Reference
// ---------------------------------------------------------------------------

export const balancingGroupSchema = z.object({
  bgId: z.string(),
  tsoArea: z.string(),
  bgCode: z.string(),
});

// ---------------------------------------------------------------------------
// Inferred TypeScript types
// ---------------------------------------------------------------------------

export type AuctionImportSessionDto = z.infer<typeof auctionImportSessionSchema>;
export type DaSettlementRowDto = z.infer<typeof daSettlementRowSchema>;
export type DaSettlementGridDto = z.infer<typeof daSettlementGridSchema>;
export type NominationComparisonRowDto = z.infer<typeof nominationComparisonRowSchema>;
export type DaNominationGridDto = z.infer<typeof daNominationGridSchema>;
export type ImbalanceRowDto = z.infer<typeof imbalanceRowSchema>;
export type DaImbalanceDailyDto = z.infer<typeof daImbalanceDailySchema>;
export type ImbalanceDaySummaryDto = z.infer<typeof imbalanceDaySummarySchema>;
export type DaImbalanceMonthlyDto = z.infer<typeof daImbalanceMonthlySchema>;
export type FeeLineItemDto = z.infer<typeof feeLineItemSchema>;
export type DaFeeBreakdownDto = z.infer<typeof daFeeBreakdownSchema>;
export type OperationalAlertDto = z.infer<typeof operationalAlertSchema>;
export type DaKpiDto = z.infer<typeof daKpiSchema>;
export type BalancingGroupDto = z.infer<typeof balancingGroupSchema>;
