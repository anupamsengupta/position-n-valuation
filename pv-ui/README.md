# pv-ui — Position & PnL Dashboard

Web frontend for the CTRM Position & Valuation platform. Connects to the `pv-app` Spring Boot simulator backend.

## Prerequisites

- Node.js 20+
- pnpm (recommended) or npm
- `pv-app` running on `localhost:8080` (see root project README)

## Quick Start

```bash
# Install dependencies
pnpm install

# Start dev server (default: http://localhost:5173)
pnpm dev
```

The Vite dev server proxies `/api/*` requests to `pv-app` at `http://localhost:8080` (configured in `vite.config.ts`), so no CORS issues.

## Environment Variables

Create a `.env.local` file (already provided for the simulator):

| Variable | Default | Description |
|----------|---------|-------------|
| `VITE_API_BASE_URL` | *(unset — uses Vite proxy)* | Set only for production builds or non-Vite hosts |
| `VITE_DEFAULT_TENANT_ID` | *(required)* | Tenant ID for simulator. Production uses auth context. |
| `VITE_DEFAULT_TENANT_NAME` | Same as tenant ID | Display name for the tenant |

## Scripts

| Command | Description |
|---------|-------------|
| `pnpm dev` | Start Vite dev server with HMR |
| `pnpm build` | TypeScript check + production build |
| `pnpm preview` | Serve the production build locally |
| `pnpm typecheck` | TypeScript strict check (no emit) |
| `pnpm test` | Run unit tests (Vitest) |
| `pnpm test:watch` | Run tests in watch mode |
| `pnpm lint` | ESLint check |

## Running with pv-app

1. Start local infrastructure from the project root:
   ```bash
   cd pv-app && docker-compose up -d
   ```
   This starts PostgreSQL (`:9432`), Redis (`:6379`), Kafka (`:9092`).

2. Start `pv-app`:
   ```bash
   mvn spring-boot:run -pl pv-app
   ```
   Backend starts on `http://localhost:8080`.

3. Start the UI:
   ```bash
   cd pv-ui && pnpm dev
   ```
   Opens at `http://localhost:3000`. The dashboard loads at `/dashboard/WIND_DE`.

## Tech Stack

- React 19, TypeScript 5.6 (strict)
- Vite 6 (dev server + build)
- TanStack Router (file-based routing)
- TanStack Query (server state, 30s refetch)
- TanStack Table (data grids)
- Zustand (client state — tenant, preferences)
- Tailwind CSS 3 (design tokens as CSS variables)
- Zod (API response validation)
- Vitest + Testing Library (unit tests)

## Project Structure

```
src/
├── api/              Fetch client, endpoint functions, query keys
├── components/
│   ├── dashboard/    Dashboard page + 7 section components
│   ├── layout/       AppShell, Header, Sidebar
│   └── primitives/   Reusable UI atoms (NumericCell, StatusBadge, etc.)
├── hooks/            TanStack Query hooks, Zustand stores
├── lib/              Utilities (number formatting, date, status)
├── routes/           TanStack Router route tree
├── schemas/          Zod schemas + shared TypeScript types
└── styles/           CSS design tokens + globals
```
