import { Link } from '@tanstack/react-router';
import * as Tooltip from '@radix-ui/react-tooltip';
import { cn } from '@/lib/cn';
import type { ComponentType, ReactNode } from 'react';

export interface SidebarLinkProps {
  to: string;
  params?: Record<string, string>;
  icon: ComponentType<{ className?: string }>;
  label: string;
  badge?: ReactNode;
  disabled?: boolean;
  disabledLabel?: string;
  indent?: boolean;
  collapsed?: boolean;
}

/**
 * Navigation link for the sidebar. Renders icon + label in expanded mode,
 * icon-only with Radix Tooltip in collapsed mode.
 * Active state is determined by TanStack Router's link matching.
 */
export function SidebarLink({
  to,
  params,
  icon: Icon,
  label,
  badge,
  disabled = false,
  disabledLabel,
  indent = false,
  collapsed = false,
}: SidebarLinkProps) {
  const displayLabel = disabledLabel ? `${label} ${disabledLabel}` : label;

  const sharedClasses = cn(
    'flex items-center gap-2 rounded text-xs font-medium transition-colors',
    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus',
    indent && !collapsed && 'pl-7',
    collapsed ? 'justify-center px-1.5 py-1.5' : 'px-2 py-1.5',
    disabled
      ? 'opacity-50 cursor-not-allowed text-text-muted'
      : 'text-text-primary hover:bg-interactive-row-selected',
  );

  const activeClasses = 'bg-interactive-row-selected border-l-2 border-interactive-focus';

  const content = (
    <>
      <Icon className="w-4 h-4 shrink-0" />
      {!collapsed && (
        <>
          <span className="truncate flex-1">{displayLabel}</span>
          {badge}
        </>
      )}
    </>
  );

  if (disabled) {
    const disabledElement = (
      <span
        className={sharedClasses}
        aria-disabled="true"
        role="link"
        tabIndex={-1}
      >
        {content}
      </span>
    );

    if (collapsed) {
      return (
        <Tooltip.Root>
          <Tooltip.Trigger asChild>{disabledElement}</Tooltip.Trigger>
          <Tooltip.Portal>
            <Tooltip.Content
              side="right"
              sideOffset={8}
              className="rounded px-2 py-1 text-xs bg-bg-tertiary text-text-primary border border-border-default shadow-md z-50"
            >
              {displayLabel}
              <Tooltip.Arrow className="fill-bg-tertiary" />
            </Tooltip.Content>
          </Tooltip.Portal>
        </Tooltip.Root>
      );
    }

    return disabledElement;
  }

  const linkElement = (
    <Link
      to={to}
      params={params ?? {}}
      className={sharedClasses}
      activeProps={{ className: activeClasses } as Record<string, string>}
      aria-label={collapsed ? displayLabel : undefined}
    >
      {content}
    </Link>
  );

  if (collapsed) {
    return (
      <Tooltip.Root>
        <Tooltip.Trigger asChild>{linkElement}</Tooltip.Trigger>
        <Tooltip.Portal>
          <Tooltip.Content
            side="right"
            sideOffset={8}
            className="rounded px-2 py-1 text-xs bg-bg-tertiary text-text-primary border border-border-default shadow-md z-50"
          >
            {displayLabel}
            {badge && <span className="ml-1">{badge}</span>}
            <Tooltip.Arrow className="fill-bg-tertiary" />
          </Tooltip.Content>
        </Tooltip.Portal>
      </Tooltip.Root>
    );
  }

  return linkElement;
}
