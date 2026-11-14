## ✨ feat(manager): Agent Health UI wired to the real backend contract (2026-11-11)

**Repo:** EDDI (`feat/agent-health-ui-wiring`, on top of `feat/agent-health-h3`, with `feat/agent-health-ui` merged in)

### What changed and why

The Manager's Agent Health UI (built on mocks) now matches what the H2b and H3 backends actually send, as written down in
§12 and §13 of [`planning/agent-health-api.md`](../../planning/agent-health-api.md). Nothing here adds an H4 feature: the
status ring stays hidden while `status` is `null`, and the profile, presets, export and owner-report-link calls still run
against mocks because the backend does not have them yet.

- **MSW handlers under the OpenAPI contract check.** The six operations the backend exposes (health, series, compare,
  portfolio, sessions, outcome suggestions) are exported as `healthHandlers` from `src/test/mocks/handlers.ts`, so
  `openapi-contract.test.ts` grades their paths and methods against `openapi-operations.json`. The later-phase mocks
  (`healthPendingHandlers`: profile, presets, export, report links) stay in `health-handlers.ts`, registered in
  `server.ts` and `browser-handlers.ts` but outside that check until their phases land. Error bodies in the mocks are
  the backend's: `{"error","message"}` and, for the sessions 403, `{"error":"forbidden","reason","message"}`.
- **Portfolio.** `sessions` is nullable and an item can carry a `hint`: the row shows "—" with the reason, never `0`; a
  key measure without a value shows its own hint. `platformIncidents[].from/to` are days, so they are formatted as
  UTC days (`formatShortDate` now treats a bare `YYYY-MM-DD` as a UTC day, which also fixes the period and "data
  through" line in time zones behind UTC). A status filter that matches nothing explains that statuses are not
  calculated yet (remove that note when H4 ships).
- **Withheld rates.** Below the outcome-coverage minimum the resolution, escalation, abandonment and deflection rates
  are `null` with their `n` kept: the card shows "—" and the hint, and the resolution split lists the sessions per source
  instead of drawing four 0 % shares. A hint now shows next to a value too ("Outcomes are still being evaluated").
- **Untestable changes.** Measures the backend never tests (`turnDurationP95`, `costPerSession`, `tokensPerTurn`,
  `turnsPerSession`, `guardrailTripRate`, `returningUserShare`, `outcomeCoverage`) read "not tested for significance"
  instead of "within normal variation".
- **Apdex** keeps its threshold: the translated label takes T from the backend's label ("Response time (Apdex, T = 4 s)").
- **Session drill-down.** It needs EDIT on the agent and review enabled on the version: a caller without EDIT sees the
  explanation without a request; the 403 `reason` tells the two denials apart, and both texts now say what is needed.
  Rows of origins that are not judged have `outcome`/`evidence` `null` and render "—"; `provisional` rows explain
  themselves; without a `version` filter the dialog says only sessions of review-enabled versions are listed.
  `sessionNo` is a step index, not an ordinal, and is only used as part of the row key.
- **Compare** says so when a version had no turns in its window (all values `null`) instead of showing a table of dashes
  alone.
- Eleven new or reworded strings in all 11 locales (one `health` block per file).

### Design decisions

- The all-null series of measures without daily values, and of ratings the caller may not read, already fall into the
  "not enough days" state; the UI never asks for `groupBy` other than `none`, so the "rating measures only with
  `groupBy=none`" rule needs no handling.
- Which measures are untestable is a list of ids in `format.ts`, because the contract carries no flag for it (§12 names the
  five; `returningUserShare` and `outcomeCoverage` are never `significant` either).

### What's next

H4 (status, reasons, profiles, presets), H4b (owner report links) and H5 (export): move the matching mocks from
`healthPendingHandlers` into `healthHandlers` and refresh `openapi-operations.json`.
