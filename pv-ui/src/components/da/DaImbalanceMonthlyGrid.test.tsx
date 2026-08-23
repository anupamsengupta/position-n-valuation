import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { DaImbalanceMonthlyGrid } from './DaImbalanceMonthlyGrid';
import type { ImbalanceDaySummaryDto } from '@/schemas/daApi';

const SAMPLE_DATA: ImbalanceDaySummaryDto[] = [
  {
    deliveryDay: '2026-08-01',
    netImbalanceMwh: '12.500',
    netCost: '450.00',
    maxDeviationMw: '3.200',
    intervalsWithImbalance: 48,
    totalIntervals: 96,
    currency: 'EUR',
  },
  {
    deliveryDay: '2026-08-02',
    netImbalanceMwh: '-5.000',
    netCost: '-180.00',
    maxDeviationMw: '1.800',
    intervalsWithImbalance: 24,
    totalIntervals: 96,
    currency: 'EUR',
  },
];

describe('DaImbalanceMonthlyGrid', () => {
  it('renders loading skeleton when isLoading is true', () => {
    render(
      <DaImbalanceMonthlyGrid data={undefined} isLoading={true} />,
    );
    expect(screen.getByLabelText('Loading data')).toBeInTheDocument();
  });

  it('renders empty state when data is empty', () => {
    render(
      <DaImbalanceMonthlyGrid data={[]} isLoading={false} />,
    );
    expect(screen.getByText(/no imbalance data for this month/i)).toBeInTheDocument();
  });

  it('renders correct column headers', () => {
    render(
      <DaImbalanceMonthlyGrid data={SAMPLE_DATA} isLoading={false} />,
    );
    expect(screen.getByText('Delivery Day')).toBeInTheDocument();
    expect(screen.getByText('Net Imbalance (MWh)')).toBeInTheDocument();
    expect(screen.getByText('Net Cost')).toBeInTheDocument();
    expect(screen.getByText('Max Deviation (MW)')).toBeInTheDocument();
    expect(screen.getByText('Intervals w/ Imbalance')).toBeInTheDocument();
  });

  it('renders correct number of rows', () => {
    render(
      <DaImbalanceMonthlyGrid data={SAMPLE_DATA} isLoading={false} />,
    );
    const grid = screen.getByRole('grid');
    expect(grid).toHaveAttribute('aria-rowcount', '3');
  });

  it('renders delivery days as clickable buttons when onDayClick is provided', () => {
    const onDayClick = vi.fn();
    render(
      <DaImbalanceMonthlyGrid data={SAMPLE_DATA} isLoading={false} onDayClick={onDayClick} />,
    );
    const dayButtons = screen.getAllByRole('button');
    expect(dayButtons).toHaveLength(2);
    expect(dayButtons[0]).toHaveTextContent('2026-08-01');
  });

  it('calls onDayClick with the correct delivery day', () => {
    const onDayClick = vi.fn();
    render(
      <DaImbalanceMonthlyGrid data={SAMPLE_DATA} isLoading={false} onDayClick={onDayClick} />,
    );
    fireEvent.click(screen.getByText('2026-08-01'));
    expect(onDayClick).toHaveBeenCalledWith('2026-08-01');
  });

  it('renders delivery days as plain text when onDayClick is not provided', () => {
    render(
      <DaImbalanceMonthlyGrid data={SAMPLE_DATA} isLoading={false} />,
    );
    expect(screen.queryAllByRole('button')).toHaveLength(0);
    expect(screen.getByText('2026-08-01')).toBeInTheDocument();
  });

  it('renders interval count fractions correctly', () => {
    render(
      <DaImbalanceMonthlyGrid data={SAMPLE_DATA} isLoading={false} />,
    );
    expect(screen.getByText('48 / 96')).toBeInTheDocument();
    expect(screen.getByText('24 / 96')).toBeInTheDocument();
  });
});
