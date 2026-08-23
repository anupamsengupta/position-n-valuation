import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DaSettlementGrid } from './DaSettlementGrid';
import type { DaSettlementRowDto } from '@/schemas/daApi';

const SAMPLE_ROWS: DaSettlementRowDto[] = [
  {
    intervalStart: '2026-08-20T00:00:00Z',
    intervalEnd: '2026-08-20T00:15:00Z',
    tradeId: 'T-001',
    tradeLegId: 'TL-001',
    direction: 'BUY',
    price: '45.50',
    volumeMw: '10.000',
    energyMwh: '2.500',
    amount: '113.75',
    cellStatus: 'IMPORTED',
    currency: 'EUR',
  },
  {
    intervalStart: '2026-08-20T00:15:00Z',
    intervalEnd: '2026-08-20T00:30:00Z',
    tradeId: 'T-002',
    tradeLegId: 'TL-002',
    direction: 'SELL',
    price: '52.30',
    volumeMw: '5.000',
    energyMwh: '1.250',
    amount: '65.375',
    cellStatus: 'IMPORTED',
    currency: 'EUR',
  },
];

describe('DaSettlementGrid', () => {
  it('renders loading skeleton when isLoading is true', () => {
    render(
      <DaSettlementGrid data={undefined} isLoading={true} timezone="Europe/Berlin" />,
    );
    expect(screen.getByLabelText('Loading data')).toBeInTheDocument();
  });

  it('renders empty state when data is empty array', () => {
    render(
      <DaSettlementGrid data={[]} isLoading={false} timezone="Europe/Berlin" />,
    );
    expect(screen.getByText(/no settlement data/i)).toBeInTheDocument();
  });

  it('renders grid with correct headers', () => {
    render(
      <DaSettlementGrid data={SAMPLE_ROWS} isLoading={false} timezone="Europe/Berlin" />,
    );
    expect(screen.getByText('Interval')).toBeInTheDocument();
    expect(screen.getByText('Trade')).toBeInTheDocument();
    expect(screen.getByText('Dir')).toBeInTheDocument();
    expect(screen.getByText('Price')).toBeInTheDocument();
    expect(screen.getByText('Volume (MW)')).toBeInTheDocument();
    expect(screen.getByText('Energy (MWh)')).toBeInTheDocument();
    expect(screen.getByText('Amount')).toBeInTheDocument();
    expect(screen.getByText('Status')).toBeInTheDocument();
  });

  it('renders the correct number of data rows', () => {
    render(
      <DaSettlementGrid data={SAMPLE_ROWS} isLoading={false} timezone="Europe/Berlin" />,
    );
    const grid = screen.getByRole('grid');
    expect(grid).toHaveAttribute('aria-rowcount', '3'); // 1 header + 2 data
  });

  it('renders trade IDs in the grid', () => {
    render(
      <DaSettlementGrid data={SAMPLE_ROWS} isLoading={false} timezone="Europe/Berlin" />,
    );
    expect(screen.getByText('T-001')).toBeInTheDocument();
    expect(screen.getByText('T-002')).toBeInTheDocument();
  });

  it('renders BUY/SELL direction indicators', () => {
    render(
      <DaSettlementGrid data={SAMPLE_ROWS} isLoading={false} timezone="Europe/Berlin" />,
    );
    expect(screen.getByText('BUY')).toBeInTheDocument();
    expect(screen.getByText('SELL')).toBeInTheDocument();
  });

  it('renders status badges for each row', () => {
    render(
      <DaSettlementGrid data={SAMPLE_ROWS} isLoading={false} timezone="Europe/Berlin" />,
    );
    const badges = screen.getAllByLabelText(/Status: Imported/i);
    expect(badges).toHaveLength(2);
  });

  it('renders empty state when data is undefined and not loading', () => {
    render(
      <DaSettlementGrid data={undefined} isLoading={false} timezone="Europe/Berlin" />,
    );
    expect(screen.getByText(/no settlement data/i)).toBeInTheDocument();
  });
});
