import { cn } from '@/lib/cn';

export interface SkeletonRowProps {
  columnWidths: number[];
  height?: number;
}

/**
 * Animated skeleton placeholder row matching grid column widths.
 */
export function SkeletonRow({ columnWidths, height = 24 }: SkeletonRowProps) {
  return (
    <div
      className="flex items-center gap-2 px-2"
      style={{ height: `${height}px` }}
      aria-hidden="true"
    >
      {columnWidths.map((width, i) => (
        <div
          key={i}
          className="h-3 rounded bg-bg-tertiary animate-pulse"
          style={{ width: `${width}%` }}
        />
      ))}
    </div>
  );
}

export interface SkeletonCardProps {
  className?: string;
}

export function SkeletonCard({ className }: SkeletonCardProps) {
  return (
    <div
      className={cn(
        'rounded-lg border border-border-default bg-bg-secondary p-4 space-y-3',
        className,
      )}
      aria-hidden="true"
    >
      <div className="h-3 w-24 rounded bg-bg-tertiary animate-pulse" />
      <div className="h-6 w-32 rounded bg-bg-tertiary animate-pulse" />
      <div className="flex gap-4">
        <div className="h-3 w-16 rounded bg-bg-tertiary animate-pulse" />
        <div className="h-3 w-16 rounded bg-bg-tertiary animate-pulse" />
      </div>
    </div>
  );
}

export interface SkeletonTableProps {
  rows?: number;
  columns?: number[];
}

export function SkeletonTable({
  rows = 12,
  columns = [15, 10, 12, 12, 12, 12, 12, 15],
}: SkeletonTableProps) {
  return (
    <div aria-busy="true" aria-label="Loading data">
      {Array.from({ length: rows }, (_, i) => (
        <SkeletonRow key={i} columnWidths={columns} />
      ))}
    </div>
  );
}
