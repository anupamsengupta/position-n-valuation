import { describe, it, expect } from 'vitest';
import {
  auctionImportSessionSchema,
  daSettlementRowSchema,
  daSettlementGridSchema,
  nominationComparisonRowSchema,
  daNominationGridSchema,
  imbalanceRowSchema,
  daImbalanceDailySchema,
  imbalanceDaySummarySchema,
  daImbalanceMonthlySchema,
  feeLineItemSchema,
  daFeeBreakdownSchema,
  operationalAlertSchema,
  daKpiSchema,
  balancingGroupSchema,
  TERMINAL_IMPORT_STATUSES,
} from './daApi';

describe('daApi schemas', () => {
  // -----------------------------------------------------------------------
  // AuctionImportSession
  // -----------------------------------------------------------------------
  describe('auctionImportSessionSchema', () => {
    it('parses a valid import session', () => {
      const input = {
        sessionId: 'sess-001',
        tenantId: 'TN_0042',
        exchange: 'EPEX_SPOT',
        biddingZone: 'DE_LU',
        deliveryDay: '2026-09-16',
        importTimestamp: '2026-09-15T14:02:00Z',
        status: 'IMPORTED',
        exchangeReportedTotalMwh: '4800.0000',
        importedTotalMwh: '4800.0000',
        intervalCount: 96,
        fileReference: 'epex_20260916.csv',
        tradeIds: ['T-001', 'T-002'],
        validationErrors: null,
        createdAt: '2026-09-15T14:00:00Z',
        completedAt: '2026-09-15T14:02:00Z',
      };
      const result = auctionImportSessionSchema.parse(input);
      expect(result.sessionId).toBe('sess-001');
      expect(result.status).toBe('IMPORTED');
      expect(result.intervalCount).toBe(96);
    });

    it('accepts numeric BigDecimal values and coerces to string', () => {
      const input = {
        sessionId: 'sess-002',
        tenantId: 'TN_0042',
        exchange: 'EPEX_SPOT',
        biddingZone: 'DE_LU',
        deliveryDay: '2026-09-16',
        importTimestamp: '2026-09-15T14:02:00Z',
        status: 'PENDING',
        exchangeReportedTotalMwh: 4800,
        importedTotalMwh: null,
        intervalCount: null,
        fileReference: null,
        tradeIds: null,
        validationErrors: null,
        createdAt: '2026-09-15T14:00:00Z',
        completedAt: null,
      };
      const result = auctionImportSessionSchema.parse(input);
      expect(result.exchangeReportedTotalMwh).toBe('4800');
      expect(result.importedTotalMwh).toBeNull();
    });

    it('rejects invalid status', () => {
      const input = {
        sessionId: 'sess-003',
        tenantId: 'TN_0042',
        exchange: 'EPEX_SPOT',
        biddingZone: 'DE_LU',
        deliveryDay: '2026-09-16',
        importTimestamp: '2026-09-15T14:02:00Z',
        status: 'INVALID_STATUS',
        exchangeReportedTotalMwh: null,
        importedTotalMwh: null,
        intervalCount: null,
        fileReference: null,
        tradeIds: null,
        validationErrors: null,
        createdAt: '2026-09-15T14:00:00Z',
        completedAt: null,
      };
      expect(() => auctionImportSessionSchema.parse(input)).toThrow();
    });
  });

  // -----------------------------------------------------------------------
  // Terminal status set
  // -----------------------------------------------------------------------
  describe('TERMINAL_IMPORT_STATUSES', () => {
    it('contains terminal statuses', () => {
      expect(TERMINAL_IMPORT_STATUSES.has('IMPORTED')).toBe(true);
      expect(TERMINAL_IMPORT_STATUSES.has('VALIDATION_FAILED')).toBe(true);
      expect(TERMINAL_IMPORT_STATUSES.has('IMPORT_FAILED')).toBe(true);
    });

    it('does not contain non-terminal statuses', () => {
      expect(TERMINAL_IMPORT_STATUSES.has('PENDING')).toBe(false);
      expect(TERMINAL_IMPORT_STATUSES.has('VALIDATING')).toBe(false);
      expect(TERMINAL_IMPORT_STATUSES.has('IMPORTING')).toBe(false);
    });
  });

  // -----------------------------------------------------------------------
  // DA Settlement
  // -----------------------------------------------------------------------
  describe('daSettlementGridSchema', () => {
    it('parses a valid settlement grid', () => {
      const input = {
        deliveryDay: '2026-09-16',
        biddingZone: 'DE_LU',
        intervalCount: 96,
        rows: [
          {
            intervalStart: '2026-09-15T22:00:00Z',
            intervalEnd: '2026-09-15T22:15:00Z',
            tradeId: 'T-001',
            tradeLegId: 'TL-001',
            direction: 'BUY',
            price: '45.50000000',
            volumeMw: '10.0000',
            energyMwh: '2.5000',
            amount: '113.7500',
            cellStatus: 'SETTLED',
            currency: 'EUR',
          },
        ],
        summary: {
          totalEnergyMwh: '2400.0000',
          totalSettlement: '109200.0000',
          vwap: '45.50000000',
          currency: 'EUR',
        },
      };
      const result = daSettlementGridSchema.parse(input);
      expect(result.rows).toHaveLength(1);
      expect(result.summary.currency).toBe('EUR');
    });
  });

  // -----------------------------------------------------------------------
  // DA Settlement Row
  // -----------------------------------------------------------------------
  describe('daSettlementRowSchema', () => {
    it('accepts BUY and SELL directions', () => {
      const base = {
        intervalStart: '2026-09-15T22:00:00Z',
        intervalEnd: '2026-09-15T22:15:00Z',
        tradeId: 'T-001',
        tradeLegId: 'TL-001',
        price: '45.50',
        volumeMw: '10.0',
        energyMwh: '2.5',
        amount: '113.75',
        cellStatus: 'SETTLED',
        currency: 'EUR',
      };
      expect(daSettlementRowSchema.parse({ ...base, direction: 'BUY' }).direction).toBe('BUY');
      expect(daSettlementRowSchema.parse({ ...base, direction: 'SELL' }).direction).toBe('SELL');
    });
  });

  // -----------------------------------------------------------------------
  // Nomination
  // -----------------------------------------------------------------------
  describe('nominationComparisonRowSchema', () => {
    it('parses a valid nomination row', () => {
      const result = nominationComparisonRowSchema.parse({
        intervalStart: '2026-09-15T22:00:00Z',
        intervalEnd: '2026-09-15T22:15:00Z',
        tradedMw: '10.0',
        nominatedMw: '10.0',
        deviationMw: '0.0',
        status: 'OK',
      });
      expect(result.status).toBe('OK');
    });

    it('accepts null values for nullable fields', () => {
      const result = nominationComparisonRowSchema.parse({
        intervalStart: '2026-09-15T22:00:00Z',
        intervalEnd: '2026-09-15T22:15:00Z',
        tradedMw: '10.0',
        nominatedMw: null,
        deviationMw: null,
        status: 'MISSING',
      });
      expect(result.nominatedMw).toBeNull();
    });
  });

  // -----------------------------------------------------------------------
  // Nomination Grid
  // -----------------------------------------------------------------------
  describe('daNominationGridSchema', () => {
    it('parses a valid nomination grid', () => {
      const result = daNominationGridSchema.parse({
        deliveryDay: '2026-09-16',
        balancingGroupId: 'BG-001',
        intervalCount: 96,
        rows: [],
        summary: {
          totalTradedMwh: '2400.0',
          totalNominatedMwh: '2400.0',
          netDeviationMwh: '0.0',
          intervalsWithDeviation: 0,
          totalIntervals: 96,
        },
        nominationSubmitted: true,
        gateClosureStatus: 'OK',
      });
      expect(result.nominationSubmitted).toBe(true);
    });
  });

  // -----------------------------------------------------------------------
  // Imbalance
  // -----------------------------------------------------------------------
  describe('imbalanceRowSchema', () => {
    it('parses a valid imbalance row', () => {
      const result = imbalanceRowSchema.parse({
        intervalStart: '2026-09-15T22:00:00Z',
        intervalEnd: '2026-09-15T22:15:00Z',
        nominatedMw: '10.0',
        actualMw: '9.5',
        imbalanceMw: '-0.5',
        rebapPrice: '80.00',
        amount: '-40.00',
        currency: 'EUR',
      });
      expect(result.imbalanceMw).toBe('-0.5');
    });
  });

  describe('daImbalanceDailySchema', () => {
    it('parses a valid daily imbalance response', () => {
      const result = daImbalanceDailySchema.parse({
        deliveryDay: '2026-09-16',
        balancingGroupId: 'BG-001',
        recordVersion: 1,
        rows: [],
        summary: {
          netImbalanceMwh: '-12.0',
          netImbalanceCost: '-960.0',
          maxIntervalImbalanceMw: '-2.0',
          maxIntervalTime: '2026-09-16T08:00:00Z',
          currency: 'EUR',
        },
        hasPriorVersion: false,
        correctionDate: null,
      });
      expect(result.recordVersion).toBe(1);
    });
  });

  describe('imbalanceDaySummarySchema', () => {
    it('parses a valid day summary', () => {
      const result = imbalanceDaySummarySchema.parse({
        deliveryDay: '2026-09-16',
        netImbalanceMwh: '-12.0',
        netCost: '-960.0',
        maxDeviationMw: '2.0',
        intervalsWithImbalance: 10,
        totalIntervals: 96,
        currency: 'EUR',
      });
      expect(result.intervalsWithImbalance).toBe(10);
    });
  });

  describe('daImbalanceMonthlySchema', () => {
    it('parses a valid monthly imbalance response', () => {
      const result = daImbalanceMonthlySchema.parse({
        yearMonth: '2026-09',
        balancingGroupId: 'BG-001',
        dailySummaries: [],
        monthlySummary: {
          totalNetImbalanceMwh: '-360.0',
          totalNetCost: '-28800.0',
          currency: 'EUR',
        },
      });
      expect(result.yearMonth).toBe('2026-09');
    });
  });

  // -----------------------------------------------------------------------
  // Fees
  // -----------------------------------------------------------------------
  describe('feeLineItemSchema', () => {
    it('parses a valid fee line item', () => {
      const result = feeLineItemSchema.parse({
        feeType: 'TRADING_FEE',
        ratePerMwh: '0.0750',
        grossVolumeMwh: '4800.0',
        feeAmount: '360.0',
        currency: 'EUR',
      });
      expect(result.feeType).toBe('TRADING_FEE');
    });
  });

  describe('daFeeBreakdownSchema', () => {
    it('parses a valid fee breakdown', () => {
      const result = daFeeBreakdownSchema.parse({
        deliveryDay: '2026-09-16',
        feeScheduleEffectiveDate: '2026-01-01',
        memberTier: 'GOLD',
        items: [],
        totalFeeAmount: '720.0',
        grossVolumeMwh: '4800.0',
        currency: 'EUR',
      });
      expect(result.memberTier).toBe('GOLD');
    });
  });

  // -----------------------------------------------------------------------
  // Alerts
  // -----------------------------------------------------------------------
  describe('operationalAlertSchema', () => {
    it('parses a valid alert', () => {
      const result = operationalAlertSchema.parse({
        alertId: 'alert-001',
        tenantId: 'TN_0042',
        category: 'AUCTION_INGESTION',
        severity: 'CRITICAL',
        alertType: 'IMPORT_FAILED',
        message: 'Import failed for DE_LU 2026-09-16',
        deliveryDay: '2026-09-16',
        biddingZone: 'DE_LU',
        sourceEventId: 'evt-001',
        raisedAt: '2026-09-15T14:05:00Z',
        acknowledgedBy: null,
        acknowledgedAt: null,
        resolvedAt: null,
        status: 'OPEN',
      });
      expect(result.severity).toBe('CRITICAL');
      expect(result.status).toBe('OPEN');
    });

    it('parses an acknowledged alert', () => {
      const result = operationalAlertSchema.parse({
        alertId: 'alert-002',
        tenantId: 'TN_0042',
        category: 'SETTLEMENT',
        severity: 'WARNING',
        alertType: 'MISMATCH',
        message: 'Settlement mismatch',
        deliveryDay: null,
        biddingZone: null,
        sourceEventId: null,
        raisedAt: '2026-09-15T14:05:00Z',
        acknowledgedBy: 'user@example.com',
        acknowledgedAt: '2026-09-15T14:10:00Z',
        resolvedAt: null,
        status: 'ACKNOWLEDGED',
      });
      expect(result.acknowledgedBy).toBe('user@example.com');
    });
  });

  // -----------------------------------------------------------------------
  // KPI
  // -----------------------------------------------------------------------
  describe('daKpiSchema', () => {
    it('parses a valid KPI summary', () => {
      const result = daKpiSchema.parse({
        netVolumeMwh: '4800.0',
        vwap: '45.50',
        settlementTotal: '218400.0',
        exchangeFees: '720.0',
        imbalanceCost: null,
        openAlertCount: 3,
        maxAlertSeverity: 'CRITICAL',
        currency: 'EUR',
      });
      expect(result.openAlertCount).toBe(3);
      expect(result.imbalanceCost).toBeNull();
    });

    it('accepts null maxAlertSeverity', () => {
      const result = daKpiSchema.parse({
        netVolumeMwh: '0.0',
        vwap: '0.0',
        settlementTotal: '0.0',
        exchangeFees: '0.0',
        imbalanceCost: null,
        openAlertCount: 0,
        maxAlertSeverity: null,
        currency: 'EUR',
      });
      expect(result.maxAlertSeverity).toBeNull();
    });
  });

  // -----------------------------------------------------------------------
  // Balancing Group
  // -----------------------------------------------------------------------
  describe('balancingGroupSchema', () => {
    it('parses a valid balancing group', () => {
      const result = balancingGroupSchema.parse({
        bgId: 'BG-001',
        tsoArea: 'DE',
        bgCode: '11X-BG-SAMPLE--1',
      });
      expect(result.bgId).toBe('BG-001');
    });
  });
});
