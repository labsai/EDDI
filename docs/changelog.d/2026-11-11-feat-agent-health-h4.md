## ✨ feat(health): Agent Health status, profiles, presets, HEALTH alerts and owner report links (2026-11-11)

**Repo:** EDDI (`feat/agent-health-h4`, on top of `feat/agent-health-h3`)

### What changed and why

Phases H4 and H4b of [`planning/agent-health-plan.md`](../../planning/agent-health-plan.md): every agent now has a
**status with reasons**, judged by its own **health profile**; health changes can raise **`HEALTH` alerts**; and a
business owner without Manager access can read a **branded, read-only report** through a link. Documented in
[`docs/agent-health.md`](../agent-health.md#status-and-reasons); the backend's answers and deviations are in the new
§14 of [`planning/agent-health-api.md`](../../planning/agent-health-api.md).

- **Status** (`HealthStatusRules`, pure): every counted measure `GREY` → `NOT_ENOUGH_DATA`; a critical measure `RED` →
  `NEEDS_ATTENTION`; any counted measure `AMBER`/`RED`, or a significant change for the worse of at least 2 pp (rates)
  or 10 % (others) → `WATCH`; else `HEALTHY`. Counted are scored measures no active override ignores; the quality
  section never counts (a status must not vary with the caller's rating rights). **Reasons**: at most five, severity
  (`RED`, `AMBER`, `INFO`) then critical first then size of change; texts like "Resolution 41 % (−12 pp since v8 on
  3 Nov)" with the explaining marker's id; the mandatory `INFO` "Resolution rests mostly on silence" when `silence` is
  more than half of the resolved sessions (plan §8). The report and the portfolio now carry real `status`, `reasons`
  and `topReason`; `sort=attention` is status severity, then worsening, then traffic; `status=` filters.
- **Presets** `support`, `sales`, `assistant`, `task` as code defaults in `HealthDefaults.PRESETS` (critical measures
  and thresholds; escalation and abandonment are banded now). Without a profile the traffic picks `support` or `task`
  as before.
- **Health profile** (`HealthProfile`, `EffectiveHealthProfile`, `HealthProfileValidator`, `HealthProfiles`): an
  unversioned admin resource per agent — MongoDB `healthprofiles`, PostgreSQL `health_profiles` — with preset,
  critical measures, thresholds, budgets (`costPerResolvedUsd`, `costPerSessionUsd`, `tokensPerTurn` make their cost
  measures scored), SLO (`agentTurnSuccess` also derives the failure bands; a per-agent `apdexTMs` the rollup applies,
  recounting the retention on change; `minTurnsForBurnAlert`) and overrides (`IGNORED` measure or section until a day;
  expired ones ignored, not refused). `GET|PUT|DELETE /agents/{agentId}/health/profile` (VIEW / EDIT) and
  `GET /health/presets`; `400 {"violations": [{path, message}]}` with dotted paths. A save or delete writes a
  `PROFILE_CHANGE` marker. Not part of agent export or sync.
- **`HEALTH` alerts** (`AlertSource.HEALTH`, `HealthAlertEvaluator`): `healthTriggers` `STATUS_CHANGE` (the 7-day
  status entered `WATCH`/`NEEDS_ATTENTION`), `BURN_RATE` (2× the error budget over the last 3 days and today, each with
  the profile's volume floor), `CRITICAL_TREND`. Evaluated after each rollup pass, edge-triggered per rule and agent
  (MongoDB `healthalertstate`, PostgreSQL `health_alert_state`), fired through the existing engine — dedupe key per
  condition, throttle, digest, outbox. `AlertCondition` gains `healthTriggers`/`healthStatuses`, `AlertMatch`
  `healthStatus`/`previousHealthStatus`/`measureId`; the validator refuses health fields on other sources and the
  other sources' fields on `HEALTH`. Health only alerts (decision 7).
- **Owner report links (H4b)** (`HealthReportLinks`, `HealthReportRateLimiter`, `RestSharedHealthReport`):
  `POST|GET|DELETE /agents/{agentId}/health/reports` (EDIT; `quality` only with the ratings right) and the public
  `GET /health/reports/{token}`. Tokens are 256 bits, shown once, stored as SHA-256 only and found by hash lookup;
  malformed, unknown, expired and revoked tokens all answer the same 404; rate limits per link, overall and for
  unknown tokens; `no-store`, `no-referrer`, `noindex`; branded from the white-label settings; only the link's
  sections, no drill-down or ids. The permit rule `quarkus.http.auth.permission.health-report` opens `GET`/`HEAD` of
  `/health/reports/*` and nothing else. The UI renders `/manage/health-report/<token>`.
- **`Measure.tested`**: every measure now says whether its kind has a significance test at all (rates, Apdex, counts)
  — derived from how `HealthMeasures` builds it, not a hand list — so `significant: false` can be told apart from
  "never tested".
- **Portfolio batching**: `IHealthOutcomeStore.countsForAgents` (Mongo and Postgres) counts the outcomes of all listed
  agents per origin in one query, so the portfolio and the alerts judge effectiveness without a query per agent.
- **GDPR**: `HealthAdminGdprParticipant` blanks the author of profiles and the creator of links.
- **MCP**: `read_agent_health_profile`, `list_health_presets` (health-read tier) and `update_agent_health_profile`
  (author tier, EDIT). No report-link tool.
- **Platform Operator**: allow-lists the profile, presets and report-link list reads and the profile save (gated);
  deleting a profile and creating or revoking a link are deliberately not granted (the write scope holds no `DELETE`,
  and a token in a tool result would be kept in the conversation and the audit ledger). Prompt teaches status,
  reasons, profiles, presets, `HEALTH` alerts and links. Operator revision 64; the OpenAPI operations snapshot gains
  the eight new operations.

### Design decisions

- Quality is left out of the status everywhere, so the report, the portfolio and the alerts agree and no rating leaks
  through a status; profiles cannot make a quality measure critical.
- Burn-rate windows are whole UTC days because the rollup keeps daily rows; documented as a limitation.
- Alert conditions are edge-triggered (stored state per rule and agent); a new rule reports a current status once.
- Report links are keyed and rate-limited by token hash, never by client address.

### Next

- Manager: the profile editor, the owner report page at `/manage/health-report/<token>` (no sign-in), and the `HEALTH`
  source in the alert-rule editor.
- H5: business join (outcome events, `externalRefs`).
