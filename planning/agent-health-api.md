# Agent Health — REST contract (for parallel backend and UI work)

> Companion to [`agent-health-plan.md`](agent-health-plan.md). It fixes the wire shapes so the Manager UI can
> be built against mocks while the backend phases land. The backend implements exactly this. If a phase
> needs a change, it updates this file in the same change and tells the UI side. Field names are
> camelCase JSON. Every timestamp is ISO-8601 UTC, and every day is `YYYY-MM-DD` (UTC).

## Conventions

- **Period**: `period=7d|30d|90d` (default `30d`) ending today (UTC, inclusive), or `from=YYYY-MM-DD&to=YYYY-MM-DD`
  (max 90 days, the turn-row retention). The previous period of equal length is the comparison baseline.
- **Version filter**: `version=<int>` (optional). Absent means all versions.
- **Origin filter**: `origin=<id>`, repeatable (optional). Absent means the preset's default set, which is the
  interactive origins for effectiveness. Origin ids (H0): `api`, `v1`, `mcp`, `email`, `slack`, `webhook`,
  `schedule`, `a2a`, `group-member`, `delegated`, `agent-test`. `agent-test` is **always excluded** unless asked
  for explicitly.
- **Bands**: `GREEN` | `AMBER` | `RED` | `GREY` (not enough data, or no threshold).
- **Measure kinds**: `SCORED` | `INFORMATIONAL` | `CHECK`.
- **Status**: `HEALTHY` | `WATCH` | `NEEDS_ATTENTION` | `NOT_ENOUGH_DATA`. `null` until H4 ships.
- **Permissions**: each section obeys its source's rule (plan §6.4). A section the caller may not read comes back
  as `{ "restricted": true, "measures": [] }`, not a 403. The whole endpoint needs VIEW on the agent; otherwise
  it answers `403`. Unknown agent: `404`.
- Errors use EDDI's usual error body. A bad query parameter gives `400` with a message naming the parameter.

## 1. Agent health — `GET /agents/{agentId}/health`

Query: `period`, `from`/`to`, `version`, `origin`.

```json
{
  "agentId": "6740…",
  "agentName": "Support",
  "period": { "from": "2026-10-12", "to": "2026-11-10", "days": 30 },
  "baseline": { "from": "2026-09-12", "to": "2026-10-11" },
  "version": null,
  "origins": ["api", "v1", "email"],
  "status": null,
  "reasons": [],
  "preset": "support",
  "generatedAt": "2026-11-10T09:00:00Z",
  "dataThrough": "2026-11-10",
  "sections": {
    "reliability":   { "restricted": false, "measures": [ /* Measure */ ] },
    "effectiveness": { "restricted": false, "coverage": 0.62, "measures": [] },
    "quality":       { "restricted": false, "measures": [] },
    "efficiency":    { "restricted": false, "measures": [] },
    "adoption":      { "restricted": false, "measures": [] },
    "business":      { "restricted": false, "measures": [] }
  },
  "checks": [
    { "id": "goldenSuite", "label": "Golden conversations", "state": "PASS", "at": "2026-11-08T12:00:00Z", "stale": false, "version": 8 }
  ]
}
```

`dataThrough` is the last day the rollup has processed. Today's numbers may lag by up to one rollup interval.

### Measure

```json
{
  "id": "agentFailureRate",
  "label": "Agent-caused failures",
  "kind": "SCORED",
  "critical": true,
  "unit": "ratio",
  "value": 0.012,
  "n": 4210,
  "interval": { "low": 0.009, "high": 0.016 },
  "baseline": 0.004,
  "delta": 0.008,
  "significant": true,
  "band": "AMBER",
  "thresholds": { "green": 0.01, "red": 0.03, "higherIsBetter": false },
  "breakdown": null,
  "hint": null
}
```

- `unit`: `ratio` (0..1, the UI renders a percentage) | `usd` | `ms` | `count` | `tokens` | `score` (0..1).
- `value` is `null` when there is no data. `band` is then `GREY`, and `hint` says why ("Fewer than 30 engaged
  sessions").
- `breakdown` is an optional list of `{ "key": "…", "label": "…", "value": …, "n": … }`. It is **mandatory** on
  `resolutionRate`, with **four** keys (plan §4.4, decision 3): `confirmed` (external outcome events and explicit
  agent actions only), `selfReported` (the model's own `report_conversation_outcome`, weak evidence), `agentClosed`
  and `silence`. It is also used for failure classes and per-origin splits.
- `thresholds` is `null` for `INFORMATIONAL` measures.

### Measure ids by section (the backend may add measures; the UI must render unknown ids generically)

| Section | ids |
| --- | --- |
| reliability | `agentFailureRate`, `platformFailureRate`, `apdex`, `turnDurationP95`, `fallbackRate` |
| effectiveness | `resolutionRate`, `escalationRate`, `abandonmentRate`, `deflectionRate`, `outcomeCoverage` |
| quality | `ratingScore`, `ratingResponseRate`, `replyThumbsUp`, `guardrailTripRate` |
| efficiency | `costPerSession`, `costPerResolvedSession`, `tokensPerTurn` |
| adoption | `sessions`, `engagedSessions`, `distinctUsers`, `returningUserShare`, `turnsPerSession` |
| business | `event:<eventType>` (one per mapped outcome event type) |

### Reason (H4)

```json
{ "measureId": "resolutionRate", "severity": "RED", "text": "Resolution 41 % (−12 pp since v8 on 3 Nov)", "markerId": "m-123" }
```

## 2. Series — `GET /agents/{agentId}/health/series`

Query: `measure=<id>` (repeatable, up to 6), `period`/`from`/`to`, `version`, `origin`, `groupBy=none|version|origin` (default `none`).

```json
{
  "agentId": "…",
  "period": { "from": "…", "to": "…", "days": 30 },
  "series": [
    { "measure": "agentFailureRate", "group": null, "unit": "ratio",
      "points": [ { "day": "2026-11-01", "value": 0.01, "n": 140 } ] }
  ],
  "markers": [
    { "id": "m-123", "at": "2026-11-03T10:12:00Z", "kind": "DEPLOYMENT", "label": "v8 deployed (production)", "version": 8 }
  ]
}
```

A day with no data is a point with `value: null`, `n: 0`, never a missing point. Marker `kind`: `DEPLOYMENT` |
`VERSION_FIRST_TURN` | `SNIPPET_CHANGE` | `GLOBAL_VARIABLE_CHANGE` | `MODEL_PRICE_CHANGE` | `PROFILE_CHANGE` | `NOTE`.

## 3. Version compare — `GET /agents/{agentId}/health/compare`

Query: `from=<version>`, `to=<version>`, optional `days` (default 14). Each side covers that version's turns: the
newer version since its first turn, the older one over its last `days` days before the newer one took over.

```json
{
  "agentId": "…",
  "from": { "version": 7, "window": { "from": "…", "to": "…" }, "sections": { /* as in §1 */ } },
  "to":   { "version": 8, "window": { "from": "…", "to": "…" }, "sections": { } },
  "deltas": [ { "measureId": "resolutionRate", "from": 0.53, "to": 0.41, "delta": -0.12, "significant": true, "better": false } ]
}
```

## 4. Portfolio — `GET /health/agents`

Query: `period` (default `7d`), `sort=attention|name|traffic` (default `attention`), `status=` (repeatable), `limit`
(default 100, max 500), `cursor`. Returns only the agents the caller may VIEW.

```json
{
  "items": [
    { "agentId": "…", "agentName": "Support", "status": "NEEDS_ATTENTION", "preset": "support",
      "topReason": { /* Reason */ }, "sessions": 1820, "sessionsDelta": -0.08,
      "keyMeasures": [ /* up to 3 Measure, each with value, band, delta */ ],
      "platformIncident": false }
  ],
  "platformIncidents": [ { "from": "…", "to": "…", "failureClass": "RATE_LIMITED", "agents": 12 } ],
  "nextCursor": null
}
```

## 5. Health profile — `GET|PUT|DELETE /agents/{agentId}/health/profile` (H4)

GET needs VIEW. PUT and DELETE need EDIT. GET returns the stored profile, or the defaults with
`"stored": false`. The body is as in plan §4.5. PUT answers `200` with the stored profile, or `400` with
`{ "violations": [ { "path": "thresholds.resolutionRate.red", "message": "…" } ] }`. Each override carries
`reason` and `until`, and an expired override is ignored, not refused.

`GET /health/presets` returns `[{ "id": "support", "label": "…", "critical": [...], "thresholds": {...} }]`.

## 6. Conversation drill-down — `GET /agents/{agentId}/health/sessions` (H3)

Query: `period`, `version`, `origin`, `outcome=` (repeatable), `evidence=` (repeatable), `limit` (≤ 100), `cursor`.
It needs conversation read permission on the agent **and** conversation review enabled on the version. Otherwise
it answers `403` with `{ "reason": "REVIEW_DISABLED" | "FORBIDDEN" }`, so the UI can explain which.

```json
{ "items": [ { "conversationId": "…", "sessionNo": 0, "startedAt": "…", "endedAt": "…", "agentVersion": 8,
  "origin": "api", "engaged": true, "outcome": "ABANDONED", "evidence": "inferred", "provisional": false,
  "turns": 6, "costUsd": 0.031, "failures": 1 } ], "nextCursor": null }
```

Outcome values: `RESOLVED`, `ASSUMED_RESOLVED`, `ESCALATED`, `ABANDONED`, `FAILED`, `DEFLECTED`, `ENDED_QUIETLY`,
`OPEN`. Evidence values: `external_event`, `conversation_id_event`, `agent_action`, `agent_closed`, `silence`,
`self_reported`, `structural`, `inferred`, `judged`.

## 7. Outcome mapping suggestions — `GET /agents/{agentId}/health/outcome-suggestions` (H3)

```json
{ "items": [ { "action": "order_confirmed", "suggested": "RESOLVED", "sessionsEndingWith": 312, "reason": "Last action of 41 % of engaged sessions" } ] }
```

## 8. Export — `GET /agents/{agentId}/health/export` (H5)

Query: `format=json|csv`, `period`, `kind=daily|sessions`. The `sessions` kind follows the drill-down
permission rule.

## 9. Owner report links (H4b)

- `POST /agents/{agentId}/health/reports` (EDIT) with `{ "expiresInDays": 30, "sections": ["reliability", "effectiveness", "efficiency", "adoption", "business"] }`
  answers `201` with `{ "id": "…", "url": "https://…/health-report/<token>", "expiresAt": "…", "sections": [...] }`.
  The `quality` section is allowed only if the creator may read ratings.
- `GET /agents/{agentId}/health/reports` (EDIT) lists the links (never the token after creation). `DELETE …/reports/{id}` revokes one.
- `GET /health/reports/{token}` is **unauthenticated**, read-only, rate-limited, and branded. It returns the §1 shape
  limited to the link's sections, with no drill-down, no conversation ids and no user data. An expired or revoked
  token answers `404`.

## 10. Notes for the UI

- Render unknown measure ids and unknown marker kinds generically (`label` is always present).
- The UI uses `status: null` to hide the status ring until H4, and `restricted: true` to show
  "You don't have access to this section".
- All numbers come formatted from `unit` client-side, using the user's locale.

## 11. UI requests

Things the Manager UI (built against mocks) assumed because the contract above is silent. The backend should
confirm or change them, and update this file when it does.

1. **`resolutionRate.breakdown` semantics.** The four entries (`confirmed`, `selfReported`, `agentClosed`, `silence`)
   are read as each source's *contribution to the rate* (a share of engaged sessions, so they add up to `value`). The UI
   draws each part as its share of the resolved sessions (`part / sum`) and flags "mostly assumed from silence" when the
   `silence` share alone is above 50 %. It works unchanged if the values are counts, because only the proportions are
   used; a missing key counts as zero.
2. **Report link list shape.** `GET …/health/reports` is assumed to return an array (a `{ "items": [...] }` wrapper is
   also accepted) of `{ "id", "expiresAt", "sections", "createdAt", "createdBy" }`; only the first three are required.
3. **Profile GET.** The UI tolerates a missing `critical`, `thresholds`, `budgets`, `slo` or `overrides` (treated as empty),
   but needs `preset`, and uses `stored: false` to say "these are the defaults". A violation `path` should be a dotted
   path into the body (`thresholds.resolutionRate.red`, `slo.agentTurnSuccess`) and `overrides[0].reason` for list
   items; the UI shows a violation under the field whose path it equals or starts.
4. **Drill-down evidence for "confirmed".** The UI opens the sessions list for the *confirmed* part of the resolution
   with `outcome=RESOLVED&outcome=ASSUMED_RESOLVED&evidence=external_event&evidence=conversation_id_event&evidence=agent_action` (`outcome` and `evidence` are **repeated** query parameters, one value each, never a delimited list);
   *self-reported* is `evidence=self_reported` (same outcomes); *agent closed* is `evidence=agent_closed` and *silence*
   is `evidence=silence`, both with `outcome=ASSUMED_RESOLVED`. The abandonment drill-down asks for `outcome=ABANDONED`
   only (`ENDED_QUIETLY` is a separate outcome).
5. **Origin default.** With no `origin` parameter the UI shows "Origins: default" and relies on the preset's interactive set.
6. **Version options.** The UI offers versions `latest … 1` (capped at 25) for the version filter and for compare, taken
   from the agent's latest version; a compare against a version with no turns should answer `404` or empty sections,
   not a 5xx.
7. **Export** is fetched through the API client (bearer token, one retry after a token refresh), not an `<a href>`, so the
   endpoint may require the `Authorization` header. The UI sends `format`, `period` and `kind=daily` only; the version and
   origin filters on screen are not sent, and the UI says so. (If the backend wants them in the export, add `version`
   and `origin` to §8 and the UI will pass them.)
8. **Check states.** `checks[].state` is rendered for `PASS` and `FAIL`; anything else reads "Not run".

## 12. Backend notes (H2b)

What the H2b backend does where this contract leaves room, and its answers to §11. Nothing above changed shape.

- **§11.5 Origin default — confirmed, made precise.** Without `origin` the agent's preset decides: an agent with
  interactive traffic in the period (or none at all) is `support` and counts `api`, `v1`, `mcp`, `email`, `slack`; an
  agent driven only by schedules, webhooks, A2A, groups or delegation is `task` and counts every origin but
  `agent-test`. `preset` and the effective `origins` are in the response.
- **§11.6 Version compare — confirmed:** a version without turns gets a side with empty values (all `value: null`),
  never a 5xx. An unknown *agent* is `404`.
- **§11.8 Check states — confirmed:** `PASS`, `FAIL`, `UNKNOWN`.
- **§11.1 Resolution breakdown — unchanged until H3:** the four keys are present with `value: null, n: 0`.
- **`significant`** is always a boolean. It is `false` for measures with no variance to test (`turnDurationP95`,
  `costPerSession`, `tokensPerTurn`, `turnsPerSession`, `guardrailTripRate`) and whenever either period is below the
  measure's minimum sample.
- **Series** accept every measure id of §1; an unknown id is `400`. Measures without daily values yet (the
  effectiveness ones, `costPerResolvedSession`, `returningUserShare`) and rating measures the caller may not read
  answer all-null points rather than an error. Rating measures have points only with `groupBy=none`.
- **Portfolio `sessions` can be `null`** (and the item then carries a `hint`, a field §4 does not show): when the period
  reaches past the turn-row retention, sessions cannot be counted; `sessionsDelta` is `null` when the baseline does (the
  90-day portfolio: its window is counted, its baseline is not). Cost and the other key measures still come from the daily
  rows; `costPerSession` then says why it has no value. `sort=traffic` orders by sessions, or by the daily rows' turns when
  sessions cannot be counted.
- **Portfolio:** `status` is `null` for every agent until H4, so a `status=` filter returns no items (the values are
  still validated). `keyMeasures` are `agentFailureRate`, `apdex`, `costPerSession`. `platformIncidents[].from/to` are
  days (`YYYY-MM-DD`), because incidents are detected per day. `nextCursor` is an opaque string.
- **Errors:** a bad parameter is `400` with `{"error": "bad_request", "message": "<names the parameter>"}`; an unknown
  agent is `404` with `{"error": "not_found", ...}`; no VIEW is `403`.
- **`dataThrough`** is the day before the earliest day of the agent that has waited for the rollup longer than one rollup
  interval, else today.
- **Count measures** (`sessions`, `engagedSessions`, `distinctUsers`) report a `baseline` scaled to the current period's
  exposure (a period ending today counts today's elapsed fraction), and `delta`/`significant` use it; the portfolio's
  `sessionsDelta` likewise. `returningUserShare` is never `significant`.
- **Apdex** names its threshold in `label` ("Response time (Apdex, T = 4 s)"); no new field. A period mixing T values is
  `value: null` with a hint.
- **Portfolio `nextCursor`** is an offset into the order computed for each request (from cached numbers): an agent whose
  numbers change between two pages can move, so a page may repeat or skip one.
- **Markers**: at most 200, the newest (≤ 150 of the agent's, ≤ 50 deployment-wide); deployment-wide markers are coalesced
  per kind and hour and shown only to agents of their global-variable tenant.

## 13. Backend notes (H3)

What the H3 backend does where this contract leaves room. Nothing above changed shape; the points marked
**deviation** are additions or precisions the UI should know about.

- **§11.1 Resolution breakdown — confirmed.** The four entries are each part's share of `n` (closed, engaged sessions of
  judged origins), so they add up to `value` (less any resolution on evidence of a later phase, none today); each entry's
  `n` is that part's session count. All four keys are always present. When the rate is withheld (coverage below
  `eddi.health.min-outcome-coverage`, default 50 %), `value` is `null` on the measure **and** on every part, `n` stays,
  and `hint` names the coverage and the minimum. `escalationRate`, `abandonmentRate` and `deflectionRate` are withheld the
  same way; `outcomeCoverage` is always shown and never `significant`.
- **§11.4 Evidence filters — confirmed.** `outcome=` and `evidence=` take the values of §6 and are AND-ed with each other
  (OR within one parameter). Self-reported resolutions are stored as `RESOLVED` with evidence `self_reported` (an outcome the
  model claimed); assumed ones as `ASSUMED_RESOLVED` with `agent_closed` or `silence`. `external_event`,
  `conversation_id_event` and `judged` are accepted and match nothing until H5/H6.
- **Deviation — `sessionNo` is the step index the session starts at**, not an ordinal: `0` for a conversation's first
  session, the index of its first turn for a later one (`3`, `17`, …). A stored session keeps its key when the retention
  removes its first turns; an undo of a session's first turn re-keys it by its new first turn.
- **Deviation — `provisional`.** `true` while the session is inside its inactivity window. Such a row carries the outcome
  known so far: `OPEN` without a signal, or the mapped / self-reported outcome (which a later turn in the window can
  still change). Agent-closed sessions are final at once. Only rows with `provisional: false` enter the rates.
- **Deviation — `outcome: null`** (and `evidence: null`) for sessions of origins that are not judged (`schedule`,
  `webhook`, `a2a`, `group-member`, `delegated`, `agent-test`). They appear in the drill-down only when such an origin is
  asked for.
- **`startedAt` / `endedAt`** come from the turn rows: a turn started `durationMs` before its first capture and was last
  active at its last capture (`updatedAt`), so a HITL resume counts from when it was resumed and reopens a closed session
  to `OPEN`. `costPerResolvedSession` is withheld (`null` + hint) with the rates when coverage is too low.
- **While the outcome evaluation catches up** (after an upgrade it backfills the turn-row retention), every outcome-based
  measure keeps its value and carries the `hint` "Outcomes are still being evaluated (through YYYY-MM-DD)", and the
  portfolio's `sessionsDelta` is `null` (the item has no `hint` field; the report's measures say it).
- **Drill-down permissions.** `403 {"error": "forbidden", "reason": …, "message": …}`. `FORBIDDEN`: the caller does not
  maintain the agent (`EDIT`, the conversation-review reader rule). `REVIEW_DISABLED`: review is off on `version`, or —
  without `version` — on the agent's current version. Without `version` the page holds only sessions of versions that have
  review enabled (of the 100 newest). The version checked is the one of the session's last turn (documented; reading a
  conversation itself applies the review rule to its own version). Rows never carry a user id. `limit` defaults to 50; `nextCursor` is an offset, like
  the portfolio's (a page may repeat or skip a session evaluated between two pages).
- **Outcome suggestions.** `items` sorted by `sessionsEndingWith` (descending), at most 20, reserved actions and actions
  mapped in the **current** version left out; `suggested` is `null` when the name matches no heuristic. The heuristics
  match whole words of the action name (split at `_`, `-`, `.` and camelCase), not substrings, and a word after
  `not`/`no`/`never` does not count — `not_done`, `unconfirmed` and `humanize` suggest nothing. VIEW suffices
  (they carry action names and counts only).
- **Adoption `sessions` / `engagedSessions`** now count sessions that **ended** in the period (open ones included) from the
  outcome rows; `turnsPerSession`, `costPerSession` and `ratingResponseRate` divide by them, and the portfolio's `sessions` /
  `sessionsDelta` too. A period older than the turn-row retention has none (`hint`).
- **`costPerResolvedSession`** = the period's spend (selected origins) ÷ resolved sessions; informational until budgets.
- **Thresholds.** `resolutionRate` green ≥ 0.7, red ≤ 0.4 (critical); `outcomeCoverage` green ≥ 0.8, red ≤ 0.5. Escalation,
  abandonment and deflection have none until health profiles (H4).
- **Series** of the effectiveness measures are per **session end day**; a day without closed sessions is `value: null, n: 0`.
- Not done in H3: the "mostly assumed from silence" **reason** (H4 status rules; the breakdown carries what it needs), and
  `healthConfig` interactive-origin overrides (plan §4.5) — which origins are judged is fixed for now.

## 14. How the Manager UI reads §12 and §13

Not a contract: a record of what the UI does with the backend's precisions, so a later change here knows what to check.

- Portfolio `sessions: null` renders "—" with the item's `hint`; `sessionsDelta: null` renders nothing. Key measures
  without a value show their own `hint`.
- `platformIncidents[].from/to` and every `period`, `baseline`, `dataThrough` and compare `window` value are UTC days.
- A rate with `value: null` shows "—" and its `hint`; the resolution split lists each part's `n` instead of shares when
  the measure and every part are `null`. A `hint` is shown whether or not there is a value.
- `significant: false` on `turnDurationP95`, `costPerSession`, `tokensPerTurn`, `turnsPerSession`, `guardrailTripRate`,
  `returningUserShare` and `outcomeCoverage` reads "not tested for significance" (the UI keeps the list of ids).
- The Apdex label's `T = …` is parsed and put into the translated label.
- The drill-down is not requested without EDIT (`canEdit` on the panel); a 403's `reason` picks the explanation.
  `outcome`/`evidence` `null` render "—"; `sessionNo` is only part of the row key.
- Statuses stay hidden while `status` is `null` (until H4); the profile, presets, export and report-link calls run against
  mocks until H4, H4b and H5 land.
- `significant: false` can also mean too few sessions in either period, not only an untestable measure; the UI reads it as "within normal variation" unless the measure is known untestable (by the optional `tested: false` flag, else by id).
