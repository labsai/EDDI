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
  existing entry of the same provider (or of provider `*`) — whatever the two model patterns. Unset prices are
  compared as metering charges them: an unset long-context cache price is the base cache price if set, else
  the tier's input price (not the base input price, which made a cut from 20 to 15 read as a raise from 10).
- **Pending changes** ([`SpendControlChangeService`](../../src/main/java/ai/labs/eddi/engine/spendcontrol/SpendControlChangeService.java)):
  stored with the whole proposed document and its base (catalog revision / stored quota); approval applies
  it only while the base is unchanged (else `STALE`, nothing applied). At most 20 pending, 7-day expiry,
  90-day retention, idempotent repeat. MongoDB `spendcontrolchanges`, PostgreSQL `spend_control_changes`.
  REST `/administration/spend-control-changes` (admin): list, read, approve, reject.
- **Approval claims, then writes.** `PENDING → APPLYING` by compare-and-set (of two concurrent approvals
  one wins, the other is `ALREADY_DECIDED`), then the write, then `APPLIED`, `STALE` or `FAILED`. A write
  that throws makes the change `FAILED` (terminal; the outcome text names only the exception type), not
  `PENDING` again. A change still `APPLYING` was interrupted mid-write (a crash): its outcome is unknown, it
  is never re-applied and retention keeps it.
- **Approval is human-only, and four-eyes with authentication.** Only an `eddi-admin` identity applies
  anything, and not the principal that proposed the change (`403 SELF_APPROVAL`, case-insensitive) — an MCP
  client acts with its user's credential, so this stops a steered model approving its own proposal over
  REST. Without authentication every caller is `anonymous` and the single operator still approves. The
  approve/reject paths join the `AGENT_ORIGINATED` request filter and the self-ungating guard; both now match
  the path without matrix parameters (`/spend-control-changes;x=1/c1/approve` routes to the same method), which
  also closes that gap for the existing `/hitl/samples`, `/hitl/autonomy` and `/hitl/analytics/rebuild` guards.
- **Compare-and-set for quotas.** `ITenantQuotaStore.compareAndSetQuota`, atomic in the MongoDB (filter on
  every stored field), PostgreSQL (one conditional statement) and in-memory stores; the MCP side reads a
  `fingerprint`. Creating the first quota of a tenant on MongoDB is an upsert keyed on `tenantId` alone with
  `$setOnInsert`, true only when it inserted — like every other write there that may insert.
- **Audit.** Every direct write, proposal and decision writes a `taskType: spendcontrol` ledger entry
  naming actor, reason, changes and the loosening parts.
- **Manager:** a *Pending changes* panel on Model Prices and Quotas (admins only) with Approve / Reject
  behind a confirmation; 11 locales. A failed first read shows an error with Retry (not an empty page); a
  selected change that someone else decided is dropped, so its dialog cannot reopen for a later proposal; on
  Quotas, Approve is disabled while the quota form has unsaved edits (Save sends the whole form, so it would
  overwrite the approved quota).
- **`list_team_cadences`** reports `"schedule": "missing"` only when the schedule does not exist; a schedule
  store failure fails the call instead of reading as a cadence that never fires.
- **Platform Operator:** may read `GET /administration/spend-control-changes` (never decide) and its prompt
  explains why a proposed price did not take effect. Revision 62 (61 is taken by three open PRs).
- **Claude Code plugin:** the `eddi-admin` skill teaches the new tools and the PROPOSED outcome.

### Design decisions

- **A new price under strict mode is a proposal.** The brief said "adding an entry applies"; under
  `REFUSE` / `REFUSE_WHEN_BUDGETED` a price *admits* calls refused today (even a $0.0001 price), so that is
  loosening. Under `ALLOW` a new entry still has to be at least as expensive as every entry of its
  provider — an exact cheap entry under a dear wildcard lowers that model's price, and so does an entry
  whose pattern overlaps nothing: a call is priced by the reported model name before the requested one, so
  a cheap `gpt-4o-2024*` takes over calls that the `gpt-4o` entry prices today. (Pattern-overlap
  detection was dropped for this reason.)
- **A failed write is `FAILED`, not `PENDING` again.** Whether it took effect is unknown; a later approval
  of a re-opened change would answer "nothing was applied", which may be false.
- **Four-eyes needs authentication.** With `authorization.enabled=false` principals cannot be told apart,
  so the check is skipped rather than blocking the single-operator deployment. With authentication, give
  the MCP client an account of its own, or have a second administrator approve.
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
- The change stores have a shared contract (`SpendControlChangeStoreContract`: round trip, status filter
  and order, CAS under contention, retention), run in memory as a unit test and against real databases by
  `MongoSpendControlChangeStoreIT` / `PostgresSpendControlChangeStoreIT` in CI. The quota CAS still has no
  real-database test.
- The Manager shows only `PENDING` changes; an `APPLYING` or `FAILED` one is visible over REST and
  `list_spend_control_changes`, not yet on the page.

**Files:** [`engine/spendcontrol/`](../../src/main/java/ai/labs/eddi/engine/spendcontrol),
[`McpSpendControlTools`](../../src/main/java/ai/labs/eddi/engine/mcp/McpSpendControlTools.java),
[`McpTeamCadenceTools`](../../src/main/java/ai/labs/eddi/engine/mcp/McpTeamCadenceTools.java),
[`McpVaultTools`](../../src/main/java/ai/labs/eddi/engine/mcp/McpVaultTools.java) (tested by `McpVaultToolsTest`),
[`docs/spend-control-changes.md`](../spend-control-changes.md), [`docs/mcp-server.md`](../mcp-server.md).
