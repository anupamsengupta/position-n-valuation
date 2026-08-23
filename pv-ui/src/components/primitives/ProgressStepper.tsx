import { cn } from '@/lib/cn';

export interface StepDefinition {
  key: string;
  label: string;
}

export interface ProgressStepperProps {
  steps: StepDefinition[];
  currentStep: string;
  terminalStates?: ReadonlySet<string>;
  errorStates?: ReadonlySet<string>;
  className?: string;
}

type StepVisualState = 'completed' | 'current' | 'error' | 'future';

function resolveStepState(
  _stepKey: string,
  stepIndex: number,
  currentIndex: number,
  terminalStates: ReadonlySet<string>,
  errorStates: ReadonlySet<string>,
  currentStep: string,
): StepVisualState {
  if (errorStates.has(currentStep) && stepIndex === currentIndex) {
    return 'error';
  }
  if (stepIndex < currentIndex) {
    return 'completed';
  }
  if (stepIndex === currentIndex) {
    // If the current step is terminal and not an error, it is completed
    if (terminalStates.has(currentStep) && !errorStates.has(currentStep)) {
      return 'completed';
    }
    return 'current';
  }
  return 'future';
}

const STEP_ICONS: Record<StepVisualState, string> = {
  completed: '\u2713', // checkmark
  current: '\u25CF',   // filled circle
  error: '\u2715',     // X
  future: '\u25CB',    // hollow circle
};

/**
 * Horizontal multi-step progress indicator for import status state machines.
 * Steps are connected by lines. Completed steps show a checkmark, the current
 * step pulses, error steps show an X, and future steps are hollow.
 */
export function ProgressStepper({
  steps,
  currentStep,
  terminalStates = new Set<string>(),
  errorStates = new Set<string>(),
  className,
}: ProgressStepperProps) {
  const currentIndex = steps.findIndex((s) => s.key === currentStep);
  const effectiveIndex = currentIndex === -1 ? 0 : currentIndex;

  const ariaText = steps[effectiveIndex]?.label ?? currentStep;

  return (
    <div
      role="progressbar"
      aria-valuemin={1}
      aria-valuemax={steps.length}
      aria-valuenow={effectiveIndex + 1}
      aria-valuetext={ariaText}
      className={cn('flex items-center gap-0', className)}
    >
      {steps.map((step, idx) => {
        const state = resolveStepState(
          step.key,
          idx,
          effectiveIndex,
          terminalStates,
          errorStates,
          currentStep,
        );

        return (
          <div key={step.key} className="flex items-center">
            {/* Connector line before this step (not on the first) */}
            {idx > 0 && (
              <div
                aria-hidden="true"
                className={cn(
                  'h-0.5 w-6',
                  state === 'completed' || state === 'current' || state === 'error'
                    ? 'bg-interactive-focus'
                    : 'bg-border-default',
                )}
              />
            )}

            {/* Step circle */}
            <div
              className={cn(
                'flex items-center justify-center rounded-full text-xs font-medium',
                'w-6 h-6',
                state === 'completed' && 'bg-status-settled text-white',
                state === 'current' && 'border-2 border-interactive-focus text-interactive-focus animate-pulse',
                state === 'error' && 'bg-red-600 dark:bg-red-500 text-white',
                state === 'future' && 'border border-border-default text-text-muted',
              )}
              title={`${step.label}: ${state}`}
            >
              <span aria-hidden="true">{STEP_ICONS[state]}</span>
            </div>
          </div>
        );
      })}
    </div>
  );
}
