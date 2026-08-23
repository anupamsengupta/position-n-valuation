import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ProgressStepper, type StepDefinition } from './ProgressStepper';

const STEPS: StepDefinition[] = [
  { key: 'PENDING', label: 'Pending' },
  { key: 'VALIDATING', label: 'Validating' },
  { key: 'IMPORTING', label: 'Importing' },
  { key: 'IMPORTED', label: 'Imported' },
];

const TERMINAL = new Set(['IMPORTED', 'IMPORT_FAILED', 'VALIDATION_FAILED']);
const ERRORS = new Set(['IMPORT_FAILED', 'VALIDATION_FAILED']);

describe('ProgressStepper', () => {
  it('renders the correct number of step circles', () => {
    const { container } = render(
      <ProgressStepper steps={STEPS} currentStep="PENDING" />,
    );
    // Each step has a circle div with a span inside
    const circles = container.querySelectorAll('[title]');
    expect(circles).toHaveLength(4);
  });

  it('marks completed steps with a checkmark', () => {
    const { container } = render(
      <ProgressStepper steps={STEPS} currentStep="IMPORTING" />,
    );
    const circles = container.querySelectorAll('[title]');
    // Steps 0 (PENDING) and 1 (VALIDATING) are completed
    expect(circles[0]?.textContent).toBe('\u2713');
    expect(circles[1]?.textContent).toBe('\u2713');
  });

  it('marks the current step with a filled circle', () => {
    const { container } = render(
      <ProgressStepper steps={STEPS} currentStep="VALIDATING" />,
    );
    const circles = container.querySelectorAll('[title]');
    // Step 1 (VALIDATING) is current
    expect(circles[1]?.textContent).toBe('\u25CF');
    expect(circles[1]?.getAttribute('title')).toBe('Validating: current');
  });

  it('marks future steps with a hollow circle', () => {
    const { container } = render(
      <ProgressStepper steps={STEPS} currentStep="PENDING" />,
    );
    const circles = container.querySelectorAll('[title]');
    // Steps 1, 2, 3 are future
    expect(circles[1]?.textContent).toBe('\u25CB');
    expect(circles[2]?.textContent).toBe('\u25CB');
    expect(circles[3]?.textContent).toBe('\u25CB');
  });

  it('marks error step with an X', () => {
    const errorSteps: StepDefinition[] = [
      { key: 'PENDING', label: 'Pending' },
      { key: 'VALIDATING', label: 'Validating' },
      { key: 'VALIDATION_FAILED', label: 'Failed' },
    ];
    const { container } = render(
      <ProgressStepper
        steps={errorSteps}
        currentStep="VALIDATION_FAILED"
        errorStates={ERRORS}
      />,
    );
    const circles = container.querySelectorAll('[title]');
    // Last step is error
    expect(circles[2]?.textContent).toBe('\u2715');
    expect(circles[2]?.getAttribute('title')).toBe('Failed: error');
  });

  it('marks terminal non-error step as completed', () => {
    const { container } = render(
      <ProgressStepper
        steps={STEPS}
        currentStep="IMPORTED"
        terminalStates={TERMINAL}
        errorStates={ERRORS}
      />,
    );
    const circles = container.querySelectorAll('[title]');
    // All steps including IMPORTED should be completed
    expect(circles[3]?.textContent).toBe('\u2713');
    expect(circles[3]?.getAttribute('title')).toBe('Imported: completed');
  });

  it('sets correct ARIA attributes', () => {
    render(
      <ProgressStepper steps={STEPS} currentStep="IMPORTING" />,
    );
    const progressbar = screen.getByRole('progressbar');
    expect(progressbar).toHaveAttribute('aria-valuemin', '1');
    expect(progressbar).toHaveAttribute('aria-valuemax', '4');
    expect(progressbar).toHaveAttribute('aria-valuenow', '3');
    expect(progressbar).toHaveAttribute('aria-valuetext', 'Importing');
  });

  it('renders connector lines between steps', () => {
    const { container } = render(
      <ProgressStepper steps={STEPS} currentStep="VALIDATING" />,
    );
    // 3 connector lines for 4 steps (no connector before the first)
    const connectors = container.querySelectorAll('.h-0\\.5');
    expect(connectors).toHaveLength(3);
  });

  it('defaults to first step when currentStep is not found', () => {
    render(
      <ProgressStepper steps={STEPS} currentStep="NONEXISTENT" />,
    );
    const progressbar = screen.getByRole('progressbar');
    expect(progressbar).toHaveAttribute('aria-valuenow', '1');
  });
});
