import { useTenantStore } from '@/hooks/useTenantStore';
import { useAsOfClock } from '@/hooks/useAsOfClock';
import { useUserPreferences } from '@/hooks/useUserPreferences';
import { cn } from '@/lib/cn';

/**
 * App header showing tenant identity, as-of toggle (disabled for v1.0),
 * and theme toggle.
 */
export function Header() {
  const { tenantId, tenantName } = useTenantStore();
  const { isNonCurrent } = useAsOfClock();
  const { theme, setPreference } = useUserPreferences();

  const toggleTheme = () => {
    const next = theme === 'light' ? 'dark' : 'light';
    setPreference('theme', next);
    document.documentElement.setAttribute('data-theme', next);
  };

  return (
    <header
      role="banner"
      className="h-10 border-b border-border-default bg-bg-secondary
                 flex items-center justify-between px-4"
    >
      <div className="flex items-center gap-4">
        {/* Tenant display */}
        <div className="flex items-center gap-1.5 text-xs">
          <span className="text-text-muted">Tenant:</span>
          <span className="font-medium text-text-primary">
            {tenantName || tenantId || 'Not set'}
          </span>
        </div>

        {/* As-Of Toggle (disabled for v1.0) */}
        <button
          type="button"
          disabled
          className={cn(
            'flex items-center gap-1 px-2 py-0.5 text-xs rounded border',
            'border-border-default text-text-muted cursor-not-allowed opacity-60',
            isNonCurrent && 'border-status-transition text-status-transition',
          )}
          title="As-of viewing is not yet available for this dashboard."
          aria-label="As-of toggle (disabled)"
        >
          <span aria-hidden="true">{isNonCurrent ? '\u23F0' : '\u23F1'}</span>
          As-Of
        </button>
      </div>

      <div className="flex items-center gap-3">
        {/* Theme toggle */}
        <button
          type="button"
          onClick={toggleTheme}
          className="px-2 py-0.5 text-xs border border-border-default rounded
                     text-text-secondary hover:text-text-primary hover:bg-bg-tertiary
                     focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus"
          aria-label={`Switch to ${theme === 'light' ? 'dark' : 'light'} theme`}
        >
          {theme === 'light' ? '\u263E' : '\u2600'}
        </button>
      </div>
    </header>
  );
}
