import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { DaAlertList } from './DaAlertList';
import type { OperationalAlertDto } from '@/schemas/daApi';

const SAMPLE_ALERTS: OperationalAlertDto[] = [
  {
    alertId: 'a1',
    tenantId: 'default',
    category: 'AUCTION_INGESTION',
    severity: 'CRITICAL',
    alertType: 'MISSING_FILE',
    message: 'Auction file for 2026-08-20 not found.',
    deliveryDay: '2026-08-20',
    biddingZone: 'DE_LU',
    sourceEventId: null,
    raisedAt: '2026-08-19T10:00:00Z',
    acknowledgedBy: null,
    acknowledgedAt: null,
    resolvedAt: null,
    status: 'OPEN',
  },
  {
    alertId: 'a2',
    tenantId: 'default',
    category: 'NOMINATION_SCHEDULING',
    severity: 'WARNING',
    alertType: 'GATE_CLOSURE_APPROACHING',
    message: 'Gate closure in 30 minutes.',
    deliveryDay: '2026-08-21',
    biddingZone: 'DE_LU',
    sourceEventId: 'evt-123',
    raisedAt: '2026-08-20T14:00:00Z',
    acknowledgedBy: 'trader1',
    acknowledgedAt: '2026-08-20T14:05:00Z',
    resolvedAt: null,
    status: 'ACKNOWLEDGED',
  },
];

describe('DaAlertList', () => {
  it('renders loading skeleton when isLoading is true', () => {
    render(
      <DaAlertList
        alerts={undefined}
        isLoading={true}
        expandedAlertId={null}
        onToggleExpand={vi.fn()}
      />,
    );
    expect(screen.getByLabelText('Loading data')).toBeInTheDocument();
  });

  it('renders empty state when alerts array is empty', () => {
    render(
      <DaAlertList
        alerts={[]}
        isLoading={false}
        expandedAlertId={null}
        onToggleExpand={vi.fn()}
      />,
    );
    expect(screen.getByText(/no alerts match/i)).toBeInTheDocument();
  });

  it('renders alert messages', () => {
    render(
      <DaAlertList
        alerts={SAMPLE_ALERTS}
        isLoading={false}
        expandedAlertId={null}
        onToggleExpand={vi.fn()}
      />,
    );
    expect(screen.getByText(/auction file.*not found/i)).toBeInTheDocument();
    expect(screen.getByText(/gate closure in 30 minutes/i)).toBeInTheDocument();
  });

  it('renders status badges', () => {
    render(
      <DaAlertList
        alerts={SAMPLE_ALERTS}
        isLoading={false}
        expandedAlertId={null}
        onToggleExpand={vi.fn()}
      />,
    );
    expect(screen.getByText('OPEN')).toBeInTheDocument();
    expect(screen.getByText('ACKNOWLEDGED')).toBeInTheDocument();
  });

  it('renders severity dots with aria-labels', () => {
    render(
      <DaAlertList
        alerts={SAMPLE_ALERTS}
        isLoading={false}
        expandedAlertId={null}
        onToggleExpand={vi.fn()}
      />,
    );
    expect(screen.getByLabelText('Severity: CRITICAL')).toBeInTheDocument();
    expect(screen.getByLabelText('Severity: WARNING')).toBeInTheDocument();
  });

  it('calls onToggleExpand when an alert row is clicked', () => {
    const onToggle = vi.fn();
    render(
      <DaAlertList
        alerts={SAMPLE_ALERTS}
        isLoading={false}
        expandedAlertId={null}
        onToggleExpand={onToggle}
      />,
    );
    fireEvent.click(screen.getByText(/auction file.*not found/i));
    expect(onToggle).toHaveBeenCalledWith('a1');
  });

  it('shows detail panel when alert is expanded', () => {
    render(
      <DaAlertList
        alerts={SAMPLE_ALERTS}
        isLoading={false}
        expandedAlertId="a1"
        onToggleExpand={vi.fn()}
      />,
    );
    // Detail panel should show alert ID
    expect(screen.getByText('a1')).toBeInTheDocument();
    expect(screen.getByText('AUCTION INGESTION')).toBeInTheDocument();
    expect(screen.getByText('MISSING_FILE')).toBeInTheDocument();
    expect(screen.getByText('2026-08-20')).toBeInTheDocument();
    expect(screen.getByText('DE_LU')).toBeInTheDocument();
  });

  it('does not show detail for non-expanded alerts', () => {
    render(
      <DaAlertList
        alerts={SAMPLE_ALERTS}
        isLoading={false}
        expandedAlertId="a1"
        onToggleExpand={vi.fn()}
      />,
    );
    // a2's detail fields should not be visible
    expect(screen.queryByText('evt-123')).not.toBeInTheDocument();
  });

  it('sets aria-expanded correctly on alert buttons', () => {
    render(
      <DaAlertList
        alerts={SAMPLE_ALERTS}
        isLoading={false}
        expandedAlertId="a1"
        onToggleExpand={vi.fn()}
      />,
    );
    const buttons = screen.getAllByRole('button');
    expect(buttons[0]).toHaveAttribute('aria-expanded', 'true');
    expect(buttons[1]).toHaveAttribute('aria-expanded', 'false');
  });

  it('renders acknowledged-by info in expanded panel', () => {
    render(
      <DaAlertList
        alerts={SAMPLE_ALERTS}
        isLoading={false}
        expandedAlertId="a2"
        onToggleExpand={vi.fn()}
      />,
    );
    expect(screen.getByText('trader1')).toBeInTheDocument();
  });
});
