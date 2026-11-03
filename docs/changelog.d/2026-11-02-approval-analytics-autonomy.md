## ✨ feat(hitl): approval analytics, sampled audit and earned autonomy (2026-11-02)

**Repo:** EDDI (`feat/approval-analytics-autonomy`, stacked on `feat/hitl-edit-on-approve`)

### What changed and why

Approval gates have a capacity: a gate that asks about everything gets rubber-stamped. This puts human attention where
it matters — see [HITL Oversight](../hitl-oversight.md).

- **Config** — one nested field, agent-level `hitlConfig.oversight {sampledAudit, autonomyPolicy}`
  ([`OversightConfig`](../../src/main/java/ai/labs/eddi/configs/hitl/model/OversightConfig.java)), validated on every save
  and import by [`OversightConfigValidation`](../../src/main/java/ai/labs/eddi/configs/hitl/OversightConfigValidation.java)
  (one line in `HitlConfigValidation`). Absent = nothing sampled, nothing recommended or suspended.
- **Approval analytics** — `ApprovalDecisionProjector` observes F3's `HitlDecisionRecorded` (`@ObservesAsync`) and stores
  one row per decided unit in a new `approval_decisions` store (MongoDB + PostgreSQL), keyed `decisionId#index` so the
  event and `ApprovalBackfillService` (rebuild from the ledger via `HitlApprovalAudit.parse`; once on first start for
  90 days, and `POST /hitl/analytics/rebuild`) never double-count. Pre-6.7 entries become `legacy:` rows that are never
  evidence. `GET /hitl/analytics/summary` / `decisions`.
- **Sampled audit** — `SampledAuditRecorder` is an `AllowedToolCallObserver` set on `ToolLoopRunner` (a setter, no
  constructor change) and called after each allowed call in both loops. Deterministic HMAC sampling keyed from the audit
  key, a per-(agent, category, UTC day) cap through `IToolQuotaStore`, a bounded write queue, card-level redaction. New
  `audit_samples` store; `/hitl/samples` review queue with one-at-a-time verdicts (CAS, no bulk).
- **Earned autonomy** — `AutonomyEvaluator` and `PromotionPatchBuilder` (pure), `AutonomyService` (apply, dismiss,
  suspend, lift; demotion from flagged samples and demote-code rejections), `autonomy_state` store, `/hitl/autonomy`.
  Apply re-checks the evidence, needs the reviewed agent version, validates and writes a new version through
  `IRestAgentStore` — never deploys.
- **Suspension overlay** — `AutonomyOverlayService` copies the effective tool-approval config with a transient,
  `@JsonIgnore` `suspendedPatterns` list plus a synthetic `WAIT_INDEFINITELY` rule per pattern (no `ConversationHitlService`
  edit: "strictest rule wins" does the rest); `ToolApprovalGate` gates an allowed call matching one with
  `suspended:<pattern>`. Applied in `LlmTask` (memory carrier and effective config), `McpCallsTask`, and carried through
  `TaskToolApprovalsResolver`'s strict merge. `ToolApprovalGate.firstExemptMatch` tells exempt from unmatched calls.
- **Security** — `X-EDDI-Agent-Call` set last on an httpcall aimed at this deployment only — its own origin
  (`SelfUrlResolver`), a loopback address or a host in the new `eddi.hitl.oversight.self-hosts`; third-party APIs see no
  EDDI header and their wire format is unchanged (`AgentCallMarker`, one line in `ApiCallExecutor`);
  `AgentOriginatedRequestFilter` answers 403 `AGENT_ORIGINATED` to marked oversight writes; `SelfUngatingGuard` delegates
  to `OversightWriteGuard` (no oversight-API writes, no change to `hitlConfig.oversight`).
- **Maintenance** — `hitl-autonomy-maintenance` system schedule (fast path right after the alerts one in
  `ScheduleFireExecutor`, reserved in `RestScheduleStore`): sample expiry, round-robin evaluation, retention, the
  first-start rebuild. Gauges refreshed per node.
- **Manager** — new `/manage/oversight` page (sidebar next to Approvals): analytics tiles and a per-category table with
  tiers, the sample review queue (one sample at a time, a flag needs a reason code, no select-all), and recommendations
  (evidence, change list, configuration preview, a second confirmation when the change leaves no gated tool) and
  suspensions (lift needs a note). `oversight` i18n namespace in all 11 locales; MSW handlers in `oversight-handlers.ts`;
  OpenAPI snapshot refreshed for the new endpoints.
- **Docs** — new [`hitl-oversight.md`](../hitl-oversight.md); one-line links in `hitl.md`, `audit-ledger.md`,
  `gdpr-compliance.md`, `SUMMARY.md`; metrics in `metrics.md` and dashboard panels 400–405; properties in
  `configuration-reference.md`.

### Not in this change

MCP read tools and the Platform Operator (see
[`planning/operator-followups/approval-analytics-autonomy.md`](../../planning/operator-followups/approval-analytics-autonomy.md));
the alerts `HITL_AUTONOMY` source and a Slack sample digest; Slack verdict buttons; an `oversight` form in the agent
editor (JSON only for now); store integration tests against Docker (serializer-level round trips instead).

```decision-log
| 2026-11-02 | Analytics read a projection (`approval_decisions`) fed by the decision event and rebuildable from the ledger | GDPR redaction empties ledger output; cancellation rows lack agents; no index on `output.*`; overflow drops | Aggregating the ledger directly; maintained `$inc` counters (not idempotent under retry/backfill) |
| 2026-11-02 | Summaries aggregate in Java over a bounded, indexed query (50k rows, `truncated` flag) | One implementation for MongoDB 6 and PostgreSQL, percentiles from fixed buckets | `$bucket` / `GROUP BY` twins per backend |
| 2026-11-02 | Re-gating is a runtime overlay with synthetic WAIT rules; promotion is a human-applied config change | Nothing ungates without a human; a suspension must never auto-execute and must not rewrite configuration | Rewriting `exempt` on demotion; an AUTO_APPROVE demotion inside ConversationHitlService |
| 2026-11-02 | No MCP write tools and no bulk sample verdict | An MCP client is often a model; bulk OK is the rubber-stamping the queue prevents | Mirroring every REST action as an MCP tool |
```
