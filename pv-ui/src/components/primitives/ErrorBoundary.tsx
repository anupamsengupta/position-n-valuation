import { Component, type ErrorInfo, type ReactNode } from 'react';

interface ErrorBoundaryProps {
  children: ReactNode;
  fallback?: ReactNode;
  onError?: (error: Error, errorInfo: ErrorInfo) => void;
}

interface ErrorBoundaryState {
  hasError: boolean;
  error: Error | null;
}

/**
 * Error boundary for catching render errors in child components.
 * Shows inline error message with retry button.
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  constructor(props: ErrorBoundaryProps) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo): void {
    this.props.onError?.(error, errorInfo);
    console.error('[ErrorBoundary]', error, errorInfo);
  }

  handleRetry = () => {
    this.setState({ hasError: false, error: null });
  };

  render(): ReactNode {
    if (this.state.hasError) {
      if (this.props.fallback) {
        return this.props.fallback;
      }
      const errorMessage = this.state.error?.message ?? 'An unexpected error occurred.';
      return (
        <div
          role="alert"
          className="flex flex-col items-center justify-center gap-3 p-8 text-text-secondary"
        >
          <p className="text-sm font-medium">Something went wrong</p>
          <p className="text-xs text-text-muted">
            {errorMessage}
          </p>
          <button
            type="button"
            onClick={this.handleRetry}
            aria-label={`Retry after error: ${errorMessage}`}
            className="px-3 py-1 text-xs font-medium rounded border border-border-default
                       bg-bg-primary text-text-primary hover:bg-bg-secondary
                       focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus"
          >
            Retry
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}
