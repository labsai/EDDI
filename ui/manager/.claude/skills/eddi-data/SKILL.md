---
name: eddi-data
description: EDDI Manager's data and plumbing conventions — routing, TanStack Query hooks, ApiClient, i18n propagation, MSW mocks and tests. Load when wiring a screen to real data or adding a route.
---

# EDDI Manager data & plumbing

AGENTS.md is the authority; this is the working summary for building a screen.

## Routing

React Router v7, declarative mode (no data router). Routes are declared in `src/app.tsx`;
pages render through `AppLayout`'s `<Outlet />`. Resource detail routes carry the version as
a query param: `/manage/channels/:id?version=2`. `src/__tests__/route-integrity.test.ts`
asserts every nav target resolves — add the route and the sidebar entry together.

## Server state — TanStack Query v5

One hook file per domain in `src/hooks/` (`use-channels.ts`, `use-agents.ts`, …). Pages
never call the API directly:

```tsx
const { data: channels, isLoading, error, refetch } = useEnrichedChannelDescriptors();
const deleteMutation = useDeleteChannel();
await deleteMutation.mutateAsync({ id, version });
```

Filtering/search is client-side `useMemo` over the query result when the list is small —
that is the existing pattern, not a server round-trip per keystroke.

## API calls

Use `ApiClient` (`src/lib/api-client.ts`) for ordinary JSON — it injects the Keycloak auth
header. Base URL is always `window.location.origin`; never hardcode. Raw `fetch` is only
justified for SSE, binary/blob and `text/plain` bodies, and **must** spread
`api.getAuthHeader()` itself — a missing header is a 401 that only shows up once OIDC is on.
`secrets.ts` uses raw fetch for historical reasons; it is debt, not a pattern to copy.

## UI state

Zustand for chat/debug stores; `useState`/`useCallback` everywhere else. No Redux.

## i18n — blocking requirement

Every string: `t("namespace.key", "English fallback")`. Add the key to
`src/i18n/locales/en.json`, then propagate translations to all 10 other locales
(de, fr, es, ar, zh, th, ja, ko, pt, hi) **in the same commit**. Each editor gets its own
namespace (`llmEditor.*`, `rulesEditor.*`). Because Arabic ships, every layout must use
logical properties.

## Tests

- Unit: Vitest + RTL in `src/pages/__tests__/`, `renderPage(type)` helper wraps
  `MemoryRouter` + `QueryClient` + `ThemeProvider`. Assert on `data-testid`.
- Mocks: MSW handlers in `src/test/mocks/handlers.ts`. Specific GET handlers must be
  registered **before** the generic `createResourceHandlers` block. Mock data should match
  the backend Java model.
- E2E: Playwright in `e2e/` — including `rtl.spec.ts` and `theme.spec.ts`, so a new screen
  must survive both Arabic and dark mode.

## Gates before you call it done

```bash
npm run test    # Vitest
npm run build   # includes tsc -b
```

Pre-commit runs `eslint --max-warnings 0` on staged files, then `npm run typecheck`.
Use `npm run typecheck` (`tsc -b`), never `tsc --noEmit` — `tsconfig.json` is a solution
file (`"files": []` plus two project references), so `--noEmit` resolves zero inputs and
exits 0 without checking anything. Never commit to `main`; branch first. Update
`HANDOFF.md` when a phase completes.
