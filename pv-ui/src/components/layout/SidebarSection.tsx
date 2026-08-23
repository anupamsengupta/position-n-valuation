import type { ReactNode } from 'react';
import { useUserPreferences } from '@/hooks/useUserPreferences';
import { cn } from '@/lib/cn';
import { ChevronIcon } from './SidebarIcons';

export interface SidebarSectionProps {
  label: string;
  sectionKey: string;
  children: ReactNode;
  collapsed?: boolean;
}

/**
 * Collapsible sidebar section with uppercase header label.
 * When the sidebar is collapsed (icon-only mode), the label and
 * expand/collapse toggle are hidden; children render directly.
 */
export function SidebarSection({
  label,
  sectionKey,
  children,
  collapsed = false,
}: SidebarSectionProps) {
  const sidebarExpanded = useUserPreferences((s) => s.sidebarExpanded);
  const toggleSectionExpanded = useUserPreferences((s) => s.toggleSectionExpanded);

  const isExpanded = sidebarExpanded[sectionKey] !== false;

  if (collapsed) {
    // In icon-only mode, render children without section chrome
    return <div className="flex flex-col gap-0.5">{children}</div>;
  }

  return (
    <div className="flex flex-col">
      <button
        type="button"
        onClick={() => toggleSectionExpanded(sectionKey)}
        className={cn(
          'flex items-center justify-between w-full px-2 py-1.5',
          'text-[10px] font-semibold text-text-muted uppercase tracking-wider',
          'hover:text-text-secondary transition-colors',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus rounded',
        )}
        aria-expanded={isExpanded}
        aria-controls={`sidebar-section-${sectionKey}`}
      >
        <span>{label}</span>
        <ChevronIcon
          className={cn(
            'w-3 h-3 transition-transform duration-150',
            isExpanded ? 'rotate-[-90deg]' : 'rotate-0',
          )}
        />
      </button>
      <div
        id={`sidebar-section-${sectionKey}`}
        role="group"
        aria-label={label}
        aria-hidden={!isExpanded}
        className={cn(
          'flex flex-col gap-0.5 overflow-hidden transition-[max-height] duration-150',
          isExpanded ? 'max-h-[500px]' : 'max-h-0',
        )}
      >
        {children}
      </div>
    </div>
  );
}
