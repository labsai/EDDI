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
  `resolutionRate`, with keys `confirmed`, `agentClosed`, `silence` (plan §4.4, decision 3), and is used for
  failure classes and per-origin splits.
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

1. **`resolutionRate.breakdown` semantics.** The UI reads each entry's `value` as that source's *contribution to the
   rate* (a share of engaged sessions, so `confirmed + agentClosed + silence = value`), and computes the silence share
   of the resolutions itself (`silence / sum`) for the "mostly assumed from silence" note (> 50 %). It works unchanged
   if the values are counts, because only the proportions are used.
2. **Report link list shape.** `GET …/health/reports` is assumed to return an array (a `{ "items": [...] }` wrapper is
   also accepted) of `{ "id", "expiresAt", "sections", "createdAt", "createdBy" }`; only the first three are required.
3. **Profile GET.** The UI tolerates a missing `critical`, `thresholds`, `budgets`, `slo` or `overrides` (treated as empty),
   but needs `preset`, and uses `stored: false` to say "these are the defaults". A violation `path` should be a dotted
   path into the body (`thresholds.resolutionRate.red`, `slo.agentTurnSuccess`) and `overrides[0].reason` for list
   items; the UI shows a violation under the field whose path it equals or starts.
4. **Drill-down evidence for "confirmed".** The UI opens the sessions list for the *confirmed* part of the resolution
   with `outcome=RESOLVED&outcome=ASSUMED_RESOLVED` and `evidence=external_event|conversation_id_event|agent_action|self_reported`;
   *agent closed* is `evidence=agent_closed` and *silence* is `evidence=silence`, both with `outcome=ASSUMED_RESOLVED`.
5. **Origin default.** With no `origin` parameter the UI shows "Origins: default" and relies on the preset's interactive set.
6. **Version options.** The UI offers versions `latest … 1` (capped at 25) for the version filter and for compare, taken
   from the agent's latest version; a compare against a version with no turns should answer `404` or empty sections,
   not a 5xx.
7. **Export** is fetched with the bearer token (not an `<a href>`), so the endpoint may require the `Authorization` header.
8. **Check states.** `checks[].state` is rendered for `PASS` and `FAIL`; anything else reads "Not run".
