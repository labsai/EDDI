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
- **The resolution rate is never one number**: four parts are always shown, each as its share of the resolved sessions —
  confirmed (external events and explicit agent actions), self-reported (the model's own report, drawn lighter), agent
  closed and silence — and a "mostly assumed from silence" note appears when silence alone is more than half (plan decision 3).
- **Restricted sections** show an access message instead of measures; the golden suite is a badge with its age and a stale flag.
- **Outcome coverage** has a "Map outcomes" call to action that lists the backend's suggestions and explains the
  `healthConfig.outcomes` mapping (applying it stays in the agent configuration; the dialog links to Agent Studio).
- **Version compare** dialog, **sessions drill-down** (403 `REVIEW_DISABLED` and `FORBIDDEN` are explained separately; each
  row opens the existing conversation view), **health profile editor** with the server's `violations` shown under the field
  each names, **owner report links** (create, copy once, list, revoke; the quality section is disabled when the panel's
  quality section is restricted), and **JSON/CSV export**. The profile and report links are offered to EDIT only.
- **Overview page** at `/manage/health` (sidebar: Monitor → Agent Health): needs-me-first list, status filter, platform
  incident banner, key measures per row, each linking to the agent's Health section.

### Design decisions

- **One feature folder, one seam**: the query hooks and the components live in `components/health/`, and every shared Manager
  import goes through [`components/health/deps.ts`](../../ui/manager/src/components/health/deps.ts). After the frontend unification
  the REST client and its types live in `@eddi/app-kit` as
  [`lib/api/health.ts`](../../ui/packages/app-kit/src/lib/api/health.ts), next to the other feature clients, because the mocks
  ([`health-handlers.ts`](../../ui/packages/app-kit/src/test/mocks/health-handlers.ts), `health-fixtures.ts`) are typed by it and the
  mock API both apps run against is app-kit's. The health query keys stay in the feature folder rather than `lib/query-keys.ts`
  because nothing else reads or invalidates them. Edits to existing files are one-liners (agent page, router, route registry, the
  two MSW entry points, locale files), plus `ApiClient.getRaw` in `@eddi/app-kit` (a GET that returns the raw response through the
  same token refresh and 401 retry, used by the export) and the export of `activeLocale` from `@eddi/ui`.
- **No chart library**: the sparkline is a small SVG. Good/caution/bad use the Manager's existing emerald, `warning` and
  `destructive` colours (not brand tokens), and every band or status also carries an icon and a word.
- **Every number and date is formatted in the app's language** (`Intl`, through the same `activeLocale` the cost formatter uses),
  and raw backend enums (outcome, evidence, origin) are translated.
- **The health mocks are not in `handlers.ts`**: the OpenAPI contract test grades that file against the backend's operation snapshot,
  which does not list these operations until the backend phases land. They are registered in `server.ts` and `browser-handlers.ts`
  and move into `handlers.ts` with the snapshot refresh.
- The `health` locale block is one contiguous object in all 11 locale files (plus `nav.health`).
- **Review follow-ups (2026-11-27):** a `#health` link opens a mounted, closed panel; no 7-day trend or drill-down is fetched
  beside a 30-day summary (with #1390's wiring, the summary keeps no placeholder, so none is shown while a new filter loads); a selected version
  beyond the 25-version cap stays an option; calendar days (`period`, `dataThrough`) are formatted as their UTC day; an
  isolated sparkline day is drawn as a dot; a failed series call shows an error with a retry of just that call; a profile
  change also invalidates the portfolio, whose query no longer keeps the previous selection's rows; Save and Reset never
  overlap. The mocks now derive compare rows from their two sides, report series bounds from the days served, and filter
  sessions by `version`.

### Open items

- The Platform Operator is untouched (the backend phases do that). It will need: `GET /agents/{id}/health`, `/health/series`,
  `/health/compare`, `GET /health/agents`, `GET /health/presets`, `GET|PUT|DELETE /agents/{id}/health/profile`,
  `/health/outcome-suggestions`, and `GET|POST|DELETE /agents/{id}/health/reports` (the last three with the usual write-confirmation rules).
- "UI requests" at the end of the REST contract list the few fields the UI would like.

**Files:** [`agent-health-panel.tsx`](../../ui/manager/src/components/health/agent-health-panel.tsx),
[`health-overview.tsx`](../../ui/manager/src/pages/health-overview.tsx), [`health.ts`](../../ui/packages/app-kit/src/lib/api/health.ts),
[`use-agent-health.ts`](../../ui/manager/src/components/health/use-agent-health.ts), [`health-handlers.ts`](../../ui/packages/app-kit/src/test/mocks/health-handlers.ts)
