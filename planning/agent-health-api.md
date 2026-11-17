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
  "nextCursor": null,
  "agentsTruncated": false
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
   with `outcome=RESOLVED&outcome=ASSUMED_RESOLVED` and `evidence=external_event|conversation_id_event|agent_action`;
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
  numbers change between two pages can move, so a page may repeat or skip one. A portfolio looks at no more than 5,000
  agents; `agentsTruncated: true` says the caller may view more, which are then in no page and no incident count.
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

## 14. Backend notes (H4, H4b)

What the H4 and H4b backend does where this contract leaves room. Points marked **deviation** add to or narrow a
shape above; the UI should know about them.

- **Status (§1).** `HEALTHY` | `WATCH` | `NEEDS_ATTENTION` | `NOT_ENOUGH_DATA`, never `null` any more. Rules: every counted
  measure `GREY` → `NOT_ENOUGH_DATA`; a critical measure `RED` → `NEEDS_ATTENTION`; any counted measure `AMBER` or `RED`, or
  a significant change for the worse of at least 2 pp (rates, scores) or 10 % (others) → `WATCH`; else `HEALTHY`. Counted:
  scored measures (a budget makes a cost measure scored) that no active override ignores. **Deviation — the quality
  section never counts** (who may read ratings differs per caller, so a status that moved with it would leak ratings, and
  the portfolio would need per-agent rating reads); profiles cannot make a quality measure critical. The status describes
  the selection asked for (`version`, `origin`).
- **Reasons (§1 `Reason`).** At most 5, ordered by severity, critical first, then size of the change. **Deviation —
  `severity` is `RED` | `AMBER` | `INFO`**: `AMBER` also for a significant change for the worse on a `GREEN` measure,
  `INFO` for reasons that do not move the status — "Resolution rests mostly on silence: 64 % of resolved sessions are
  assumed because people went quiet" (always when `silence` is more than half of the resolved sessions, also while the rate
  is withheld; answers §11.1's "mostly assumed from silence" server-side) and "Not enough data: …" with
  `NOT_ENOUGH_DATA`. `text` formats: "Resolution 41 % (−12 pp since v8 on 3 Nov)" (with `markerId`: the newest
  `DEPLOYMENT` or `VERSION_FIRST_TURN` marker of the period), "… (−12 pp vs. the previous period)" (no marker), "Agent-caused
  failures 8 % (target ≤ 1 %)" (a band without a significant change). `measureId` is `null` only on a generic
  "Not enough data in this period".
- **Deviation — `Measure.tested`** (boolean, every measure in REST and MCP output, placed after `significant`): `true`
  when the measure's kind has a significance test at all (rates, Apdex, counts), `false` when it never does
  (`turnDurationP95`, `costPerSession`, `costPerResolvedSession`, `tokensPerTurn`, `turnsPerSession`,
  `guardrailTripRate`, `returningUserShare`, `outcomeCoverage`). A property of the measure, not of the period: derived from
  how `HealthMeasures` builds each measure (built once over a period where every source has data; tested when that build
  carries a test). `significant` keeps its meaning; with `tested: false` it is always `false` and says nothing. The UI
  should prefer this over its own list.
- **No index.** §1 has no field for the weighted 0–100 index; none is computed (plan decision 1).
- **Portfolio (§4).** Real `status` and `topReason` (the first reason; **never a `markerId`** — markers are read per agent
  by the report). The status uses the same rules over the portfolio's period (default 7 days), so it can differ from a
  30-day report. `sort=attention` = status severity (`NEEDS_ATTENTION`, `WATCH`, `HEALTHY`, `NOT_ENOUGH_DATA`), then the
  largest significant worsening, then traffic, then name. `status=` filters.
- **Profile (§5), confirmed with precisions.** `GET` answers the **effective** profile (preset merged with the stored
  profile, `stored`, `updatedAt`, `updatedBy`): `thresholds` lists every banded measure except the cost ones,
  `slo.agentTurnSuccess` and `slo.minTurnsForBurnAlert` are filled with their defaults (0.99, 50), `slo.apdexTMs` is `null`
  for the deployment's `eddi.health.apdex-t`. `PUT` replaces the stored profile with the body **as sent** (a field left out
  falls back to the preset); every field is optional; `agentId` in the body must match the path or be absent. `400` body:
  `{"error": "bad_request", "message": …, "violations": [{"path", "message"}]}` with paths `preset`, `critical[i]`,
  `thresholds.<id>`, `thresholds.<id>.green|red`, `budgets.<field>`, `slo.<field>`, `overrides`, `overrides[i]`,
  `overrides[i].measure|section|state|reason|until`. `DELETE` answers `204`. **Deviation — `budgets` fields**:
  `costPerResolvedUsd`, `costPerSessionUsd`, `tokensPerTurn`; each makes its measure `SCORED` (green ≤ budget, red ≥ 1.5 ×
  budget). `slo.agentTurnSuccess` also sets `agentFailureRate`'s bands (green = 1 − SLO, red = 5 × that) unless
  `thresholds.agentFailureRate` is given. `overrides[].until` is a day `YYYY-MM-DD`, inclusive, at most 366 days ahead;
  `state` is `IGNORED` only. **Deviation — `defaulted`** (response only, ignored on a request): the fields that come from
  the preset or the deployment default rather than the stored profile, among `preset`, `critical`, `thresholds`,
  `budgets`, `slo.agentTurnSuccess`, `slo.apdexTMs`, `slo.minTurnsForBurnAlert`, `overrides` — all of them when
  `stored: false`. Writing a response back would pin those defaults, so a client sends only what is stored plus its
  change; returning to the preset is `DELETE`.
- **Presets (§5).** `GET /health/presets` returns `[{id, label, description, critical, thresholds}]` with `thresholds` as
  `{measureId: {green, red, higherIsBetter}}` — the §1 `Thresholds` shape. **Deviation:** `description` is an addition.
- **Report links (§9).** `POST` body `{expiresInDays (1–90, default 30), sections (default all but quality), period
  ("7d" | "30d" | "90d", default "30d")}` — **deviation: `period` is an addition**. `201` answers `{id, url, path, expiresAt,
  sections, period}` — **`path` is an addition** (`/manage/health-report/<token>`; `url` is it on `eddi.self.base-url` when
  set, else on the request's base). `quality` without the ratings right is `403` (not 400). At most 20 live links per
  agent (`400` beyond). §11.2 confirmed: `GET …/reports` returns a **bare array** of `{id, expiresAt, sections, period,
  createdAt, createdBy}`, live links only. `DELETE …/reports/{id}` is `204`, `404` for an unknown id. **Addition:**
  `DELETE …/reports` revokes every link of the agent (EDIT), `200 {"revoked": n}`. The 20-link cap is enforced after
  the insert (a link that is not among the agent's first 20 live ones in creation order is deleted again and refused),
  so concurrent creations cannot exceed it. Create and revoke are recorded in the audit ledger
  (`HEALTH_REPORT_LINK_CREATED|REVOKED`, with the link id, never the token); so are profile saves and deletes
  (`HEALTH_PROFILE_SAVED|DELETED`) — the generic admin-action filter leaves `/agents/*` out. Deleting an agent revokes
  its links; a permanent delete also removes its profile and alert states.
- **Decision — creator rights are re-checked on every read (strict).** A link stores its creator's principal, team
  subjects and administrator flag at creation. Every public read asks
  `ResourceAccessGuard.checkPrincipalLevel(agentId, creator, teams, admin, EDIT)` — the request-less access check the
  schedules use — against the agent's ownership, grants, shares and space **as they are now**. `DENIED` answers `404`
  (like a revoked link; the link shows again if the access returns), `UNKNOWN` (descriptor store unavailable) `503`.
  A creator blanked by GDPR erasure is `DENIED` wherever access is enforced. Not re-checkable without the creator's
  token, and therefore a snapshot: their roles (administrator; the rating roles that allowed `quality`) and leaving a
  team. `docs/security.md` says plainly that removing a person's role does not end their links, and to revoke them.
- **Public report.** `GET /health/reports/{token}` answers `{agentName, period, baseline, status, reasons, preset,
  generatedAt, dataThrough, sections, checks, expiresAt, brand}` — **deviation from "§1 shape"**: no `agentId`,
  `version` or `origins`; `sections` holds only the link's sections (others are absent, not `restricted`); `reasons` only
  of those sections; `checks` only with `reliability`; `brand` is `{productName, tagline, poweredBy, logoUrl, primaryColor}`
  from the white-label settings. **`status` and `reasons` are judged over the link's sections only** (with the agent's
  effective profile and markers), so a link never reveals the state of a section it leaves out; no measure carries a
  `hint` (override reasons are admin notes) and every `Reason.markerId` is `null`. `404 {"error": "not_found"}` for a
  malformed, unknown, expired or revoked token alike — a malformed one before any rate limit or lookup. Rate limits per
  node: a token not found within 10 minutes is charged **before** its database lookup to 10/min per client address and
  60/min overall (refunded when found live); only a found link is charged to 30/min per link and 300/min for all found
  reports; `429` with `Retry-After: 60`. The client address is Quarkus's resolved remote address (forwarded only from
  `quarkus.http.proxy.trusted-proxies`). Headers: `Cache-Control: no-store`, `Referrer-Policy: no-referrer` (set on the
  Vert.x response, replacing the deployment-wide header rather than adding a second), `X-Robots-Tag: noindex`. The token
  is scrubbed from trace spans (`url.path` and friends become `/health/reports/{token}`); proxy access logs are the
  deployment's to mask.
- **UI route for the owner report: `/manage/health-report/<token>`.** The Manager is already served without sign-in under
  `/manage/*` (static-assets permit); its router must render this route without starting the Keycloak login, and read
  the report from `GET /health/reports/{token}` without an `Authorization` header. **Not built yet**: the page and the
  link screens follow in a later PR; until then the docs and the operator say "REST only for now".
- **HEALTH alerts (plan §6.4).** `source: "HEALTH"`, `condition: {healthTriggers: ["STATUS_CHANGE" | "BURN_RATE" |
  "CRITICAL_TREND"], healthStatuses?: ["WATCH" | "NEEDS_ATTENTION"]}`. Evaluated after each rollup pass over a 7-day window;
  burn-rate windows are **whole UTC days** (long = last 3 days, short = today; 2× the error budget in both, each with
  `slo.minTurnsForBurnAlert` turns). Edge-triggered per rule and agent. The alert `match` adds `healthStatus`,
  `previousHealthStatus` and `measureId`; `conversationId` and `agentVersion` are `null`. The per-rule state moves on
  only for conditions whose fire was recorded — a failed fire, or no alert engine, leaves the condition unseen so the
  next pass fires it again. An agent with a day of the status window or its baseline still waiting for the rollup since
  before the pass is not judged that pass. The rollup reads an agent's Apdex T from the store, not the 60 s profile
  cache, so a recount on another node never uses the old T.
- **Not done:** the Manager's alert-rule editor does not offer `HEALTH` yet; report links have no MCP tool, and the
  Platform Operator may list links but neither create nor revoke them (a token in a tool result would be stored in its
  conversation and the audit ledger; its write scope holds no `DELETE`). It may save a profile (gated), not delete one.
