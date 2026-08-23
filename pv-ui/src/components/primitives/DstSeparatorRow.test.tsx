import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { DstSeparatorRow } from './DstSeparatorRow';

/** Helper to render inside a <table><tbody> wrapper for valid HTML. */
function renderInTable(ui: React.ReactElement) {
  return render(<table><tbody>{ui}</tbody></table>);
}

describe('DstSeparatorRow', () => {
  it('renders spring-forward text', () => {
    const { container } = renderInTable(
      <DstSeparatorRow type="spring-forward" columnCount={8} />,
    );
    const td = container.querySelector('td');
    expect(td?.textContent).toContain('DST spring-forward');
    expect(td?.textContent).toContain('02:00');
    expect(td?.textContent).toContain('03:00');
    expect(td?.textContent).toContain('skipped');
  });

  it('renders fall-back text', () => {
    const { container } = renderInTable(
      <DstSeparatorRow type="fall-back" columnCount={8} />,
    );
    const td = container.querySelector('td');
    expect(td?.textContent).toContain('DST fall-back');
    expect(td?.textContent).toContain('02:00');
    expect(td?.textContent).toContain('03:00');
    expect(td?.textContent).toContain('repeated');
  });

  it('sets colSpan to columnCount', () => {
    const { container } = renderInTable(
      <DstSeparatorRow type="spring-forward" columnCount={12} />,
    );
    const td = container.querySelector('td');
    expect(td?.getAttribute('colspan')).toBe('12');
  });

  it('has role="presentation" on the tr', () => {
    const { container } = renderInTable(
      <DstSeparatorRow type="fall-back" columnCount={6} />,
    );
    const tr = container.querySelector('tr');
    expect(tr?.getAttribute('role')).toBe('presentation');
  });

  it('applies amber background class', () => {
    const { container } = renderInTable(
      <DstSeparatorRow type="spring-forward" columnCount={8} />,
    );
    const td = container.querySelector('td');
    expect(td?.className).toContain('bg-status-transition');
  });
});
