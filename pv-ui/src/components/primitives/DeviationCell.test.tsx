import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { DeviationCell } from './DeviationCell';

describe('DeviationCell', () => {
  it('renders zero as neutral (no color class)', () => {
    const { container } = render(
      <DeviationCell value={0} precision="MW" />,
    );
    const wrapper = container.firstElementChild;
    expect(wrapper?.className).not.toContain('text-status-transition');
    expect(wrapper?.className).not.toContain('text-red');
  });

  it('renders values within minor threshold as neutral', () => {
    const { container } = render(
      <DeviationCell value={0.05} precision="MW" />,
    );
    const wrapper = container.firstElementChild;
    expect(wrapper?.className).not.toContain('text-status-transition');
    expect(wrapper?.className).not.toContain('text-red');
  });

  it('renders values at minor boundary as neutral', () => {
    const { container } = render(
      <DeviationCell value={0.1} precision="MW" />,
    );
    const wrapper = container.firstElementChild;
    // 0.1 is at the boundary -- at or below minor is neutral
    expect(wrapper?.className).not.toContain('text-status-transition');
  });

  it('renders values between minor and significant as amber', () => {
    const { container } = render(
      <DeviationCell value={2.5} precision="MW" />,
    );
    const wrapper = container.firstElementChild;
    expect(wrapper?.className).toContain('text-status-transition');
  });

  it('renders values above significant threshold as red', () => {
    const { container } = render(
      <DeviationCell value={10.0} precision="MW" />,
    );
    const wrapper = container.firstElementChild;
    expect(wrapper?.className).toContain('text-red');
  });

  it('uses custom thresholds', () => {
    const { container } = render(
      <DeviationCell
        value={3.0}
        precision="MW"
        thresholds={{ minor: 1.0, significant: 2.0 }}
      />,
    );
    const wrapper = container.firstElementChild;
    // 3.0 > 2.0 significant threshold => red
    expect(wrapper?.className).toContain('text-red');
  });

  it('renders negative values with absolute comparison', () => {
    const { container } = render(
      <DeviationCell value={-3.0} precision="MW" />,
    );
    const wrapper = container.firstElementChild;
    // abs(-3.0) = 3.0, between minor (0.1) and significant (5.0) => amber
    expect(wrapper?.className).toContain('text-status-transition');
  });

  it('includes severity word in aria-label for significant deviation', () => {
    const { container } = render(
      <DeviationCell value={10.0} precision="MW" />,
    );
    const wrapper = container.querySelector('[aria-label]');
    expect(wrapper?.getAttribute('aria-label')).toContain('significant deviation');
  });

  it('includes severity word in aria-label for minor deviation', () => {
    const { container } = render(
      <DeviationCell value={2.0} precision="MW" />,
    );
    const wrapper = container.querySelector('[aria-label]');
    expect(wrapper?.getAttribute('aria-label')).toContain('minor deviation');
  });

  it('renders null value as em-dash', () => {
    const { container } = render(
      <DeviationCell value={null} precision="MW" />,
    );
    expect(container.textContent).toBe('\u2014');
  });

  it('renders undefined value as em-dash', () => {
    const { container } = render(
      <DeviationCell value={undefined} precision="MW" />,
    );
    expect(container.textContent).toBe('\u2014');
  });
});
