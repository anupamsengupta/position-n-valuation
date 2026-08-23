import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DaSettlementSummaryRow } from './DaSettlementSummaryRow';

describe('DaSettlementSummaryRow', () => {
  const summary = {
    totalEnergyMwh: '2400.000',
    totalSettlement: '108000.00',
    vwap: '45.00',
    currency: 'EUR',
  };

  it('renders total energy label', () => {
    render(<DaSettlementSummaryRow summary={summary} />);
    expect(screen.getByText('Total Energy:')).toBeInTheDocument();
  });

  it('renders total settlement label', () => {
    render(<DaSettlementSummaryRow summary={summary} />);
    expect(screen.getByText('Total Settlement:')).toBeInTheDocument();
  });

  it('renders VWAP label', () => {
    render(<DaSettlementSummaryRow summary={summary} />);
    expect(screen.getByText('VWAP:')).toBeInTheDocument();
  });

  it('renders currency unit', () => {
    render(<DaSettlementSummaryRow summary={summary} />);
    expect(screen.getByText('EUR/MWh')).toBeInTheDocument();
  });

  it('has accessible region role', () => {
    render(<DaSettlementSummaryRow summary={summary} />);
    expect(screen.getByRole('region', { name: /settlement summary/i })).toBeInTheDocument();
  });
});
