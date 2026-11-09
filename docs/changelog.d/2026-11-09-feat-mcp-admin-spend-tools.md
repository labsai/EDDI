## ✨ feat(mcp): admin tools for prices, the tenant quota, team cadences and vault names — looser spend waits for a human (2026-11-09)

**Repo:** EDDI (`feat/mcp-admin-spend-tools`)

### What changed and why

An administrator's AI client operating EDDI over MCP could not set the model price catalog, the tenant
monthly cost quota or a team's cadences, so it had to drive the Manager in a browser. Those writes were
left off MCP on purpose — an MCP client is often a model, and lowering a price to $0 or raising a quota is
raising a budget, which is exactly what a prompt injection wants. This change adds the tools without
giving that up: **a write that only makes spending stricter applies at once; a write with any loosening
part applies nothing and becomes a pending change that an `eddi-admin` approves in the Manager**, with the
approver as actor. See [Spend-Control Changes](../spend-control-changes.md).

- **12 new MCP tools** (108 → 120). Admin only: `read_tenant_quota`, `update_model_prices`,
  `update_tenant_quota`, `list_spend_control_changes`, `withdraw_spend_control_change`, `list_vault_keys`.
  Author tier (`eddi-admin`, `eddi-editor`, as REST): `list_team_cadences`, `create_team_cadence`,
  `delete_team_cadence`, `read_lesson_consolidation`, `schedule_lesson_consolidation`,
  `unschedule_lesson_consolidation`. No tool approves or rejects.
- **Classifier** ([`SpendControlDiff`](../../src/main/java/ai/labs/eddi/engine/spendcontrol/SpendControlDiff.java)),
  conservative by construction: a removed entry, any lower or cleared price, a looser policy, a raised or
  disabled quota loosen; a new entry is stricter only under `ALLOW` and only if it costs at least every
  existing entry whose pattern may match the same model.
- **Pending changes** ([`SpendControlChangeService`](../../src/main/java/ai/labs/eddi/engine/spendcontrol/SpendControlChangeService.java)):
  stored with the whole proposed document and its base (catalog revision / stored quota); approval applies
  it only while the base is unchanged (else `STALE`, nothing applied). At most 20 pending, 7-day expiry,
  90-day retention, idempotent repeat. MongoDB `spendcontrolchanges`, PostgreSQL `spend_control_changes`.
  REST `/administration/spend-control-changes` (admin): list, read, approve, reject.
- **Approval is human-only.** Only an `eddi-admin` identity applies anything; the approve/reject paths join
  the `AGENT_ORIGINATED` request filter and the self-ungating guard.
- **Compare-and-set for quotas.** `ITenantQuotaStore.compareAndSetQuota`, atomic in the MongoDB (filter on
  every stored field), PostgreSQL (one conditional statement) and in-memory stores; the MCP side reads a
  `fingerprint`.
- **Audit.** Every direct write, proposal and decision writes a `taskType: spendcontrol` ledger entry
  naming actor, reason, changes and the loosening parts.
- **Manager:** a *Pending changes* panel on Model Prices and Quotas (admins only) with Approve / Reject
  behind a confirmation; 11 locales.
- **Platform Operator:** may read `GET /administration/spend-control-changes` (never decide) and its prompt
  explains why a proposed price did not take effect. Revision 62 (61 is taken by three open PRs).
- **Claude Code plugin:** the `eddi-admin` skill teaches the new tools and the PROPOSED outcome.

### Design decisions

- **A new price under strict mode is a proposal.** The brief said "adding an entry applies"; under
  `REFUSE` / `REFUSE_WHEN_BUDGETED` a price *admits* calls refused today (even a $0.0001 price), so that is
  loosening. Under `ALLOW` a new entry still has to be at least as expensive as every overlapping entry —
  an exact cheap entry under a dear wildcard lowers that model's price.
- **Mixed writes are proposed whole**, not split, so an approver sees one coherent document and nothing
  half-applies.
- **Cadences need no proposal**: a run is skipped when the team budget is spent and charged to the tenant
  budget; consolidation is capped at $5 per run. Over MCP a cadence must still carry `maxCostPerRun`
  (≤ $100), narrower than REST.
- **Vault:** names, grants and references only (the existing REST listing, which already drops value and
  checksum); writing secrets stays off MCP.

### Not done / next

- No Slack or e-mail notification of a pending change yet (logged at `WARN`, metric
  `eddi.spendcontrol.changes`); the HITL Slack channel is conversation-bound and needs its own card type.
- No integration test against real MongoDB/PostgreSQL for the two new stores and the quota CAS.

**Files:** [`engine/spendcontrol/`](../../src/main/java/ai/labs/eddi/engine/spendcontrol),
[`McpSpendControlTools`](../../src/main/java/ai/labs/eddi/engine/mcp/McpSpendControlTools.java),
[`McpTeamCadenceTools`](../../src/main/java/ai/labs/eddi/engine/mcp/McpTeamCadenceTools.java),
[`McpVaultTools`](../../src/main/java/ai/labs/eddi/engine/mcp/McpVaultTools.java),
[`docs/spend-control-changes.md`](../spend-control-changes.md), [`docs/mcp-server.md`](../mcp-server.md).
