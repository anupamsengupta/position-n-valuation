import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { DaAlertFilterBar } from './DaAlertFilterBar';
import { useDaFilters } from '@/hooks/useDaFilters';

describe('DaAlertFilterBar', () => {
  beforeEach(() => {
    // Reset store state before each test
    useDaFilters.setState({
      alertStatusFilter: null,
      alertSeverityFilter: [],
      alertCategoryFilter: null,
    });
  });

  it('renders status dropdown with "All Statuses" default', () => {
    render(<DaAlertFilterBar onClear={vi.fn()} />);
    const statusSelect = screen.getByLabelText('Status');
    expect(statusSelect).toHaveValue('__all__');
  });

  it('renders severity checkboxes', () => {
    render(<DaAlertFilterBar onClear={vi.fn()} />);
    expect(screen.getByLabelText('Critical')).toBeInTheDocument();
    expect(screen.getByLabelText('Warning')).toBeInTheDocument();
    expect(screen.getByLabelText('Info')).toBeInTheDocument();
  });

  it('renders category dropdown', () => {
    render(<DaAlertFilterBar onClear={vi.fn()} />);
    const categorySelect = screen.getByLabelText('Category');
    expect(categorySelect).toHaveValue('__all__');
  });

  it('does not show clear button when no filters active', () => {
    render(<DaAlertFilterBar onClear={vi.fn()} />);
    expect(screen.queryByText('Clear filters')).not.toBeInTheDocument();
  });

  it('shows clear button when status filter is set', () => {
    useDaFilters.setState({ alertStatusFilter: 'OPEN' });
    render(<DaAlertFilterBar onClear={vi.fn()} />);
    expect(screen.getByText('Clear filters')).toBeInTheDocument();
  });

  it('shows clear button when severity filter is set', () => {
    useDaFilters.setState({ alertSeverityFilter: ['CRITICAL'] });
    render(<DaAlertFilterBar onClear={vi.fn()} />);
    expect(screen.getByText('Clear filters')).toBeInTheDocument();
  });

  it('calls onClear when clear button is clicked', () => {
    useDaFilters.setState({ alertStatusFilter: 'OPEN' });
    const onClear = vi.fn();
    render(<DaAlertFilterBar onClear={onClear} />);
    fireEvent.click(screen.getByText('Clear filters'));
    expect(onClear).toHaveBeenCalled();
  });

  it('has accessible toolbar role', () => {
    render(<DaAlertFilterBar onClear={vi.fn()} />);
    expect(screen.getByRole('toolbar', { name: /alert filters/i })).toBeInTheDocument();
  });

  it('has accessible fieldset for severity', () => {
    render(<DaAlertFilterBar onClear={vi.fn()} />);
    // The fieldset legend is sr-only but should exist for a11y
    expect(screen.getByText('Severity filter')).toBeInTheDocument();
  });
});
