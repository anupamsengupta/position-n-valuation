import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DaStatusBadge, type DaStatus } from './DaStatusBadge';

/** All 18 statuses with expected label, icon, and a class substring for color. */
const STATUS_EXPECTATIONS: Array<{
  status: DaStatus;
  label: string;
  icon: string | null;
  colorClass: string;
}> = [
  { status: 'IMPORTED',          label: 'Imported',  icon: '\u2713', colorClass: 'bg-status-settled' },
  { status: 'IMPORTING',         label: 'Importing', icon: '\u25E0', colorClass: 'bg-status-forward' },
  { status: 'VALIDATING',        label: 'Validating', icon: '\u25E0', colorClass: 'bg-status-forward' },
  { status: 'VALIDATED',         label: 'Validated', icon: '\u2713', colorClass: 'bg-status-settled' },
  { status: 'PENDING',           label: 'Pending',   icon: '\u25F7', colorClass: 'bg-bg-tertiary' },
  { status: 'VALIDATION_FAILED', label: 'Failed',    icon: '\u2715', colorClass: 'bg-red' },
  { status: 'IMPORT_FAILED',     label: 'Failed',    icon: '\u2715', colorClass: 'bg-red' },
  { status: 'BUY',               label: 'BUY',       icon: null,     colorClass: 'bg-status-settled' },
  { status: 'SELL',              label: 'SELL',       icon: null,     colorClass: 'bg-red' },
  { status: 'CRITICAL',          label: 'CRIT',       icon: null,     colorClass: 'bg-red' },
  { status: 'WARNING',           label: 'WARN',       icon: null,     colorClass: 'bg-status-transition' },
  { status: 'INFO',              label: 'INFO',       icon: null,     colorClass: 'bg-status-forward' },
  { status: 'OPEN',              label: 'Open',       icon: null,     colorClass: 'bg-red' },
  { status: 'ACKNOWLEDGED',      label: "Ack'd",      icon: null,     colorClass: 'bg-status-transition' },
  { status: 'RESOLVED',          label: 'Resolved',   icon: '\u2713', colorClass: 'bg-status-settled' },
  { status: 'OK',                label: 'OK',         icon: '\u2713', colorClass: 'bg-status-settled' },
  { status: 'DEVIATION',         label: 'Deviation',  icon: null,     colorClass: 'bg-status-transition' },
  { status: 'MISSING',           label: 'Missing',    icon: null,     colorClass: 'bg-red' },
];

describe('DaStatusBadge', () => {
  it.each(STATUS_EXPECTATIONS)(
    'renders $status with label "$label"',
    ({ status, label }) => {
      render(<DaStatusBadge status={status} />);
      const badge = screen.getByLabelText(`Status: ${label}`);
      expect(badge).toBeInTheDocument();
      expect(badge.textContent).toContain(label);
    },
  );

  it.each(STATUS_EXPECTATIONS)(
    'renders $status with correct color class containing "$colorClass"',
    ({ status, label, colorClass }) => {
      render(<DaStatusBadge status={status} />);
      const badge = screen.getByLabelText(`Status: ${label}`);
      expect(badge.className).toContain(colorClass);
    },
  );

  it.each(STATUS_EXPECTATIONS.filter((s) => s.icon !== null))(
    'renders $status with icon "$icon"',
    ({ status, label, icon }) => {
      render(<DaStatusBadge status={status} />);
      const badge = screen.getByLabelText(`Status: ${label}`);
      const iconSpan = badge.querySelector('[aria-hidden="true"]');
      expect(iconSpan).not.toBeNull();
      expect(iconSpan?.textContent).toBe(icon);
    },
  );

  it.each(STATUS_EXPECTATIONS.filter((s) => s.icon === null))(
    'renders $status without an icon',
    ({ status, label }) => {
      render(<DaStatusBadge status={status} />);
      const badge = screen.getByLabelText(`Status: ${label}`);
      const iconSpan = badge.querySelector('[aria-hidden="true"]');
      expect(iconSpan).toBeNull();
    },
  );

  it('renders spinner animation for IMPORTING', () => {
    render(<DaStatusBadge status="IMPORTING" />);
    const badge = screen.getByLabelText('Status: Importing');
    const iconSpan = badge.querySelector('[aria-hidden="true"]');
    expect(iconSpan?.className).toContain('animate-spin');
  });

  it('renders spinner animation for VALIDATING', () => {
    render(<DaStatusBadge status="VALIDATING" />);
    const badge = screen.getByLabelText('Status: Validating');
    const iconSpan = badge.querySelector('[aria-hidden="true"]');
    expect(iconSpan?.className).toContain('animate-spin');
  });

  it('does not render spinner for non-spinner statuses', () => {
    render(<DaStatusBadge status="IMPORTED" />);
    const badge = screen.getByLabelText('Status: Imported');
    const iconSpan = badge.querySelector('[aria-hidden="true"]');
    expect(iconSpan?.className).not.toContain('animate-spin');
  });

  it('supports size "md"', () => {
    render(<DaStatusBadge status="BUY" size="md" />);
    const badge = screen.getByLabelText('Status: BUY');
    expect(badge.className).toContain('text-sm');
  });

  it('supports size "sm" (default)', () => {
    render(<DaStatusBadge status="BUY" />);
    const badge = screen.getByLabelText('Status: BUY');
    expect(badge.className).toContain('text-xs');
  });

  it('has aria-label on every badge', () => {
    render(<DaStatusBadge status="CRITICAL" />);
    const badge = screen.getByLabelText('Status: CRIT');
    expect(badge).toBeInTheDocument();
  });

  it('passes className through', () => {
    render(<DaStatusBadge status="OK" className="custom-class" />);
    const badge = screen.getByLabelText('Status: OK');
    expect(badge.className).toContain('custom-class');
  });
});
