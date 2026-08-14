import { cn } from '@/lib/cn';

export interface EmptyStateProps {
  message: string;
  className?: string;
}

/**
 * Centered empty state placeholder for screens with no data.
 */
export function EmptyState({ message, className }: EmptyStateProps) {
  return (
    <div
      className={cn(
        'flex items-center justify-center py-12 text-text-muted text-sm',
        className,
      )}
      role="status"
    >
      <p>{message}</p>
    </div>
  );
}
