import type { Config } from 'tailwindcss';

const config: Config = {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  darkMode: ['selector', '[data-theme="dark"]'],
  theme: {
    extend: {
      colors: {
        bg: {
          primary: 'var(--color-bg-primary)',
          secondary: 'var(--color-bg-secondary)',
          tertiary: 'var(--color-bg-tertiary)',
          'grid-even': 'var(--color-bg-grid-row-even)',
        },
        text: {
          primary: 'var(--color-text-primary)',
          secondary: 'var(--color-text-secondary)',
          muted: 'var(--color-text-muted)',
        },
        status: {
          settled: 'var(--color-status-settled)',
          transition: 'var(--color-status-transition)',
          forward: 'var(--color-status-forward)',
          today: 'var(--color-status-today)',
        },
        numeric: {
          negative: 'var(--color-negative)',
          positive: 'var(--color-positive)',
        },
        border: {
          default: 'var(--color-border-default)',
          grid: 'var(--color-border-grid)',
        },
        interactive: {
          focus: 'var(--color-focus-ring)',
          'row-selected': 'var(--color-row-selected)',
          'row-hover': 'var(--color-row-hover)',
        },
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', '-apple-system', 'sans-serif'],
        mono: ['JetBrains Mono', 'Fira Code', 'ui-monospace', 'monospace'],
      },
      fontSize: {
        xs: '0.6875rem',
        sm: '0.75rem',
        base: '0.8125rem',
        lg: '0.9375rem',
        xl: '1.125rem',
        '2xl': '1.5rem',
      },
    },
  },
  plugins: [],
};

export default config;
