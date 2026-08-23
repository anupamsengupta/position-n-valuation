import { useRef } from 'react';
import { cn } from '@/lib/cn';

export interface CategoryDefinition {
  key: string;
  label: string;
  count: number;
}

export interface CategoryBadgeStripProps {
  categories: CategoryDefinition[];
  activeCategory: string | null;
  onCategoryClick: (categoryKey: string | null) => void;
  className?: string;
}

/**
 * Horizontal filter badge strip for alert/category dashboards.
 * Prepends an "All" badge with total count. Supports keyboard navigation
 * with arrow keys (roving tabindex) and Enter/Space activation.
 */
export function CategoryBadgeStrip({
  categories,
  activeCategory,
  onCategoryClick,
  className,
}: CategoryBadgeStripProps) {
  const totalCount = categories.reduce((sum, cat) => sum + cat.count, 0);

  // "All" badge is represented by null activeCategory
  const allItems: Array<{ key: string | null; label: string; count: number }> = [
    { key: null, label: 'All', count: totalCount },
    ...categories.map((c) => ({ key: c.key as string | null, label: c.label, count: c.count })),
  ];

  const buttonRefs = useRef<(HTMLButtonElement | null)[]>([]);

  const handleKeyDown = (e: React.KeyboardEvent, currentIndex: number) => {
    let nextIndex: number | null = null;
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') {
      nextIndex = (currentIndex + 1) % allItems.length;
    } else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
      nextIndex = (currentIndex - 1 + allItems.length) % allItems.length;
    }
    if (nextIndex !== null) {
      e.preventDefault();
      buttonRefs.current[nextIndex]?.focus();
    }
  };

  return (
    <div
      role="toolbar"
      aria-label="Category filter"
      className={cn('flex items-center gap-1.5 flex-wrap', className)}
    >
      {allItems.map((item, idx) => {
        const isActive = activeCategory === item.key;
        const isZeroCount = item.count === 0 && item.key !== null;

        return (
          <button
            key={item.key ?? '__all__'}
            ref={(el) => { buttonRefs.current[idx] = el; }}
            type="button"
            aria-pressed={isActive}
            tabIndex={isActive ? 0 : -1}
            className={cn(
              'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium transition-colors',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus',
              isActive
                ? 'bg-interactive-focus text-white'
                : 'bg-bg-secondary text-text-secondary hover:bg-bg-tertiary',
              isZeroCount && !isActive && 'opacity-50',
            )}
            onClick={() => onCategoryClick(item.key)}
            onKeyDown={(e) => handleKeyDown(e, idx)}
          >
            {item.label}
            <span
              className={cn(
                'inline-flex items-center justify-center rounded-full text-xs min-w-[1.25rem] h-5 px-1',
                isActive
                  ? 'bg-white/20'
                  : 'bg-bg-tertiary',
              )}
            >
              {item.count}
            </span>
          </button>
        );
      })}
    </div>
  );
}
