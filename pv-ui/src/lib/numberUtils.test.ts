import { describe, it, expect } from 'vitest';
import { formatNumber, parseNumericValue, isNegativeValue } from './numberUtils';

describe('parseNumericValue', () => {
  it('parses string numbers', () => {
    expect(parseNumericValue('12345.6789')).toBe(12345.6789);
  });

  it('parses numeric values', () => {
    expect(parseNumericValue(42.5)).toBe(42.5);
  });

  it('returns null for null/undefined', () => {
    expect(parseNumericValue(null)).toBeNull();
    expect(parseNumericValue(undefined)).toBeNull();
  });

  it('returns null for non-numeric strings', () => {
    expect(parseNumericValue('abc')).toBeNull();
  });

  it('handles zero', () => {
    expect(parseNumericValue('0')).toBe(0);
    expect(parseNumericValue(0)).toBe(0);
  });

  it('handles negative values', () => {
    expect(parseNumericValue('-123.45')).toBe(-123.45);
  });
});

describe('formatNumber', () => {
  it('formats PRICE with min 2 decimals', () => {
    const result = formatNumber('42.5', 'PRICE', { locale: 'en-US' });
    expect(result).toBe('42.50');
  });

  it('formats PRICE with up to 8 decimals', () => {
    const result = formatNumber('42.12345678', 'PRICE', { locale: 'en-US' });
    expect(result).toBe('42.12345678');
  });

  it('formats MONETARY with currency suffix', () => {
    const result = formatNumber('1234.56', 'MONETARY', { locale: 'en-US', currency: 'EUR' });
    expect(result).toBe('1,234.56 EUR');
  });

  it('formats MW with suffix', () => {
    const result = formatNumber('15.5', 'MW', { locale: 'en-US' });
    expect(result).toBe('15.50 MW');
  });

  it('formats MWH with suffix', () => {
    const result = formatNumber('9300.25', 'MWH', { locale: 'en-US' });
    expect(result).toBe('9,300.25 MWh');
  });

  it('returns em-dash for null values', () => {
    const result = formatNumber(null, 'MONETARY');
    expect(result).toBe('\u2014');
  });

  it('handles negative numbers with default (minus sign)', () => {
    const result = formatNumber('-123.45', 'MONETARY', { locale: 'en-US', negativeStyle: 'red' });
    expect(result).toBe('-123.45');
  });

  it('handles negative numbers with parentheses style', () => {
    const result = formatNumber('-123.45', 'MONETARY', {
      locale: 'en-US',
      negativeStyle: 'parentheses',
    });
    expect(result).toBe('(123.45)');
  });

  it('formats with de-DE locale by default', () => {
    const result = formatNumber('1234.56', 'MONETARY');
    // de-DE uses . as thousand separator and , as decimal separator
    expect(result).toContain('1');
    expect(result).toContain('234');
  });

  it('formats PERCENTAGE', () => {
    const result = formatNumber('95.5', 'PERCENTAGE', { locale: 'en-US' });
    expect(result).toBe('95.50%');
  });
});

describe('isNegativeValue', () => {
  it('returns true for negative values', () => {
    expect(isNegativeValue('-5')).toBe(true);
    expect(isNegativeValue(-5)).toBe(true);
  });

  it('returns false for positive values', () => {
    expect(isNegativeValue('5')).toBe(false);
    expect(isNegativeValue(5)).toBe(false);
  });

  it('returns false for null/undefined', () => {
    expect(isNegativeValue(null)).toBe(false);
    expect(isNegativeValue(undefined)).toBe(false);
  });

  it('returns false for zero', () => {
    expect(isNegativeValue(0)).toBe(false);
    expect(isNegativeValue('0')).toBe(false);
  });
});
