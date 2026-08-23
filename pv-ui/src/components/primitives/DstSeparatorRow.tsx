import { cn } from '@/lib/cn';

export interface DstSeparatorRowProps {
  type: 'spring-forward' | 'fall-back';
  columnCount: number;
}

const DST_MESSAGES: Record<DstSeparatorRowProps['type'], string> = {
  'spring-forward': 'DST spring-forward: 02:00\u201303:00 CET skipped',
  'fall-back': 'DST fall-back: 02:00\u201303:00 repeated',
};

/**
 * Visual separator row for DST transition days in settlement grids.
 * Full-width row spanning all columns with an amber background and
 * descriptive text. Not focusable during grid keyboard navigation.
 */
export function DstSeparatorRow({ type, columnCount }: DstSeparatorRowProps) {
  return (
    <tr role="presentation">
      <td
        colSpan={columnCount}
        className={cn(
          'px-3 py-1.5 text-xs font-medium text-center',
          'bg-status-transition/20 text-status-transition',
          'border-y border-border-default',
        )}
      >
        {DST_MESSAGES[type]}
      </td>
    </tr>
  );
}
