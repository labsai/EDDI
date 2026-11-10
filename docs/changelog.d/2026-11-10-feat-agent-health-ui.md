## ✨ feat(manager): Agent Health UI, built against the REST contract with mocks (2026-11-10)

**Repo:** EDDI (`feat/agent-health-ui`)

### What changed and why

The Manager side of Agent Health ([plan](../../planning/agent-health-plan.md), [REST contract](../../planning/agent-health-api.md)),
built in parallel with the backend phases against MSW mocks that follow the contract's wire shapes. Nothing here talks to a
real health endpoint yet: until the backend lands, the Health section on the agent page shows the request error and the
overview page is empty, and `npm run dev` in mock mode shows the full set of scenarios.

- **Health section on the agent page** (collapsed until opened, so nothing is fetched on every page view): period (7/30/90 days),
  version and origin filters; status ring with the top reasons (hidden while the backend sends `status: null`); one card per
  section with value in its unit, band as icon plus word plus colour, change against the previous period with significance,
  the interval as a range, the `hint` on grey measures, and a sparkline per measure with change markers.
- **The resolution rate is never one number**: confirmed, agent closed and silence are always shown, and a "mostly assumed
  from silence" note appears when silence is more than half of the resolutions (plan decision 3).
- **Restricted sections** show an access message instead of measures; the golden suite is a badge with its age and a stale flag.
- **Outcome coverage** has a "Map outcomes" call to action that lists the backend's suggestions and explains the
  `healthConfig.outcomes` mapping (applying it stays in the agent configuration).
- **Version compare** dialog, **sessions drill-down** (403 `REVIEW_DISABLED` and `FORBIDDEN` are explained separately; each
  row opens the existing conversation view), **health profile editor** with the server's `violations` shown under the field
  each names, **owner report links** (create, copy once, list, revoke; the quality section is disabled when the panel's
  quality section is restricted), and **JSON/CSV export**. The profile and report links are offered to EDIT only.
- **Overview page** at `/manage/health` (sidebar: Monitor → Agent Health): needs-me-first list, status filter, platform
  incident banner, key measures per row, each linking to the agent's Health section.

### Design decisions

- **One seam for the frontend unification**: every shared import of the health files goes through
  [`components/health/deps.ts`](../../ui/manager/src/components/health/deps.ts), so the refactor that moves `lib/api`, `components/ui`
  and `lib/utils` into `@eddi/app-kit` rewrites one file here. Edits to existing files are one-liners (agent page, router, route
  registry, the two MSW entry points, locale files).
- **No chart library**: the sparkline is a small SVG; colours are the semantic tokens (`warning`, `destructive`, emerald) and every
  band or status also carries an icon and a word.
- **The health mocks are not in `handlers.ts`**: the OpenAPI contract test grades that file against the backend's operation snapshot,
  which does not list these operations until the backend phases land. They are registered in `server.ts` and `browser-handlers.ts`
  and move into `handlers.ts` with the snapshot refresh.
- The `health` locale block is one contiguous object in all 11 locale files (plus `nav.health`).

### Open items

- The Platform Operator is untouched (the backend phases do that). It will need: `GET /agents/{id}/health`, `/health/series`,
  `/health/compare`, `GET /health/agents`, `GET /health/presets`, `GET|PUT|DELETE /agents/{id}/health/profile`,
  `/health/outcome-suggestions`, and `GET|POST|DELETE /agents/{id}/health/reports` (the last three with the usual write-confirmation rules).
- "UI requests" at the end of the REST contract list the few fields the UI would like.

**Files:** [`agent-health-panel.tsx`](../../ui/manager/src/components/health/agent-health-panel.tsx),
[`health-overview.tsx`](../../ui/manager/src/pages/health-overview.tsx), [`health.ts`](../../ui/manager/src/lib/api/health.ts),
[`use-agent-health.ts`](../../ui/manager/src/hooks/use-agent-health.ts), [`health-handlers.ts`](../../ui/manager/src/test/mocks/health-handlers.ts)
