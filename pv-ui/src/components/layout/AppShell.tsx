import type { ReactNode } from 'react';
import { Sidebar } from './Sidebar';
import { Header } from './Header';

export interface AppShellProps {
  children: ReactNode;
}

/**
 * Application shell with collapsible sidebar nav and header.
 * Sidebar width transitions are handled by the Sidebar component itself
 * via CSS transition on width (220px expanded, 48px collapsed).
 */
export function AppShell({ children }: AppShellProps) {
  return (
    <div className="flex h-screen overflow-hidden bg-bg-primary">
      <Sidebar />
      <div className="flex flex-col flex-1 overflow-hidden">
        <Header />
        <main className="flex-1 overflow-auto p-4">
          {children}
        </main>
      </div>
    </div>
  );
}
