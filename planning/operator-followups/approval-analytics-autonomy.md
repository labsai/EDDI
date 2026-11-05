# Operator follow-up — approval analytics, sampled audit and earned autonomy (`feat/approval-analytics-autonomy`)

Nothing in this file is built. It is the input for the combined operator/MCP follow-up PR ("F9 integration"), updated
to what `feat/approval-analytics-autonomy` actually shipped. The feature is documented in
[`docs/hitl-oversight.md`](../../docs/hitl-oversight.md).

## Platform Operator

### `tool-scopes.ts` — `READ_ENDPOINTS`

Add, with a comment block like the ratings one:

- `GET /hitl/analytics/summary`
- `GET /hitl/autonomy`
- `GET /hitl/autonomy/{agentId}/category` (query `category`)
- `GET /hitl/samples/pending-count`

Keep **excluded**, and say why in the comment:

- `GET /hitl/samples`, `GET /hitl/samples/{id}` — they carry users' redacted tool arguments (and results with
  `includeResult`), which would go to the operator's model provider.
- `GET /hitl/analytics/decisions` — per-row conversation ids and deciders.
- **Every write**: `PATCH /hitl/samples/{id}`, `POST /hitl/autonomy/{agentId}/apply|dismiss|suspend|lift`,
  `POST /hitl/analytics/rebuild`. The server refuses them anyway when they come from an agent's tool call
  (`X-EDDI-Agent-Call` → 403 `AGENT_ORIGINATED`), but the operator must not even hold them.

Add a predicate like the ratings one so the prompt section below appears only when those reads are granted.

### `system-prompt.ts` — new section

- **The tiers**: `GATED` (asks), `READY` (an open PROMOTE recommendation), `PROMOTED` (a human applied one; the category
  is exempt and sampled), `SUSPENDED` (re-gated at runtime after a flagged sample or by hand).
- **`hitlConfig.oversight.sampledAudit`** — `rules[{match, rate, category?}]` (≤50; most specific wins; default category
  `tool:<match>`), `maxSamplesPerCategoryPerDay` (50, 1..1000), `reviewWithin` (`P3D`, PT1H..P30D), `includeResult`
  (false). Only calls the gate *allowed* are sampled; mocked calls, calls without a result and group member turns never.
- **`hitlConfig.oversight.autonomyPolicy`** — `defaults` + `categories{<category>: {...}}`; only named categories are
  evaluated. Fields and defaults: `promoteAfter` 30 (5..10000), `minUneditedRate` 0.9, `window` P30D (P1D..P365D),
  `demoteOnReasonCodes` [] (must be in `review.reasonCodes` or the built-in set), `demoteOnFlaggedSamples` 1 (0..100),
  `promotedSampleRate` 0.10 (0.01..1), `cooldownAfterDemotion` P14D, `evidenceChannels` [MANAGER, REST, SLACK] (MCP may
  be added; TIMEOUT/SYSTEM refused).
- **Categories** are F3's approval categories: `tool:<requireApproval pattern>`, a rule's `approvalCategory`, or a
  sample-rule category. RULE and group categories get advisory recommendations only.
- **How to answer "which categories are close to autonomy"**: `GET /hitl/analytics/summary?agentId=…&groupBy=category`
  (unedited rate, decisions) plus `GET /hitl/autonomy?agentId=…` (tiers) and `GET /hitl/autonomy/{agentId}/category`
  (evidence evaluated now, with the reason it is or is not eligible).
- **`suspended:<pattern>`** as a gate reason on a pending tool call means an automatic demotion: a reviewer flagged
  what that category did. Only a human lifts it (Manager → Oversight).
- **Human actions** — reviewing samples, applying/dismissing recommendations, suspending and lifting happen on
  **Manager → Oversight** (`/manage/oversight`). The operator points the admin there and never attempts them. Apply
  writes a new agent version and does **not** deploy it.
- Failure modes an admin will ask about: 409 `EVIDENCE_CHANGED` (the evidence no longer holds, e.g. a FALSE_CLAIM
  rejection since), 409 `AGENT_CHANGED` (the agent has a newer version than the one reviewed; reload), 409
  `RECOMMENDATION_CHANGED`, 409 `SAMPLE_ALREADY_REVIEWED`, 400 `UNKNOWN_REASON_CODE`, 400 `NOT_APPLICABLE` (advisory),
  400 `WINDOW_TOO_LONG` (>365 days), 403 `AGENT_ORIGINATED`.
- Properties: `eddi.hitl.samples.enabled`, `eddi.hitl.autonomy.suspension-cache-ttl` (how long until a suspension
  reaches every pod), `eddi.hitl.autonomy.maintenance-interval`, retention properties.

### `gate-guard.ts`

Refuse any agentstore PATCH/PUT whose path or value names `oversight`, `sampledAudit` or `autonomyPolicy`, as it already
does for `toolApprovals` (the server's `OversightWriteGuard` refuses the same for approved agent tool calls).

### Revision

One combined bump of `operator-revision.json` for all eight features.

## MCP read tools (same PR)

Thin wrappers; each service method is public and already REST-backed. Read-only; access as the REST endpoint (admins
and approvers see all agents, editors need `agentId` with EDIT). Role tier: the same tier as the ratings read tools.

| Tool | Args | Calls |
| --- | --- | --- |
| `summarize_approvals` | `agentId?`, `groupId?`, `category?`, `from?`, `to?`, `groupBy?` | `ApprovalAnalyticsService.summarize(DecisionFilter, GroupBy)` (scope via `OversightAccess.requireReadScope`, window via `OversightWindow`) |
| `list_autonomy_recommendations` | `agentId?` | `AutonomyService.list(agentId, limit)` |
| `get_autonomy_state` | `agentId`, `category` | `AutonomyService.get(agentId, category)` |
| `count_pending_audit_samples` | `agentId?` | `AuditSampleService.count(SampleFilter.pending(agentId))` |

The same PR updates the tool count in `McpToolContractTest`, `McpToolFilter`, `McpRoleTierParityTest` and
`docs/mcp-server.md`. **No MCP tool** for a verdict, apply, dismiss, suspend, lift or rebuild — ever.

## Also deferred from this branch

- Alerts source `HITL_AUTONOMY` (`PROMOTION_READY`, `SUSPENDED`, `SAMPLE_BACKLOG`) and a daily Slack "N samples awaiting
  review" digest.
- Slack OK/Flag buttons for samples (pairs with F7's card work).
- A form for `hitlConfig.oversight` in the Manager's agent HITL editor (JSON only for now).
- MCP-client half of the `X-EDDI-Agent-Call` header (EDDI's MCP server has no oversight write tools).
