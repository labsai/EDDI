## 🔀 chore(manager): Agent Health wiring rebuilt on the reviewed UI and the rebuilt H3 (2026-11-27)

**Repo:** EDDI (`feat/agent-health-ui-wiring`, PR #1390)

### What changed and why

The wiring branch was 299 commits behind its base. It now merges the rebuilt `feat/agent-health-h3` and then
`feat/agent-health-ui` (#1387 after its review), so it wires the reviewed UI and not an older copy of it.

- **Where the code lives.** The health REST client is
  [`lib/api/health.ts`](../../ui/packages/app-kit/src/lib/api/health.ts) in `@eddi/app-kit`, and the mocks are
  [`health-handlers.ts`](../../ui/packages/app-kit/src/test/mocks/health-handlers.ts) and
  [`health-fixtures.ts`](../../ui/packages/app-kit/src/test/mocks/health-fixtures.ts) next to the other app-kit mocks,
  as #1387 placed them. The wiring changes to the client (`laterHealthPhasesAvailable`, the H3-shaped types) moved with
  it; nothing health-related is left under `ui/manager/src/test/mocks` except its test, which sits with the other mock
  tests.
- **The portfolio type has `agentsTruncated`.** `HealthReports.Portfolio` always sends it (the caller may view more agents
  than one portfolio looks at). The UI type and the fixtures now carry it. The overview does not show it yet.

### Design decisions

Where both branches fixed the same review finding differently, one fix was kept:

- **Summary while a new filter loads:** #1390's rule (no placeholder summary, so the loading state shows and never the
  previous selection's figures) instead of #1387's `keepPreviousData` plus a settled query. With no placeholder there is
  no summary for an older query to sit beside, so the settled query had nothing left to do; #1387's test that no 7-day
  trend is fetched beside a 30-day summary stays and passes.
- **`#health` on a mounted, closed panel:** #1390's effect keyed on the location, which also reopens on a repeated
  navigation to the same hash; #1387's render-time adjustment was dropped.
- **Failed trends:** #1387's `ErrorState` with a retry of the failed calls only, and its wording ("Some trends could not
  be loaded"). The sparkline label keeps #1390's wording, which names the range's bounds.
- **Isolated sparkline day:** #1387's separate dot path (`dots`) instead of an `h0` segment in the line path.
- **Compare options:** #1390's older-side list (the versions below the newer one) combined with #1387's `keep` argument,
  so a resolved version beyond the 25-version cap stays selectable on both sides.
- **Mocks:** #1390's period-derived series range and markers, sessions origin and version filter, with #1387's typed
  period lookup and its compare fixture (rows read from both sides).
