## ✨ feat(groups): per-team monthly budgets — a calendar-month dollar cap per group, metered from the cost ledger, enforced at the gate and inside the run (2026-11-02)

**Repo:** EDDI (`feat/team-budgets`, stacked on `feat/cost-accounting`)

### What changed and why

Several standing teams share one tenant and one monthly cost budget. One busy team could spend the whole month and
leave the others nothing, and nothing said who had spent what. A group can now carry a `teamBudget`; see
[Team Budgets](../team-budgets.md).

- **Config** — [`TeamBudgetConfig`](../../src/main/java/ai/labs/eddi/configs/groups/model/TeamBudgetConfig.java)
  (`monthlyBudgetUsd`, `onExhausted`, `oversight.teams`) on `AgentGroupConfiguration`, validated at save
  ([`TeamBudgetConfigValidator`](../../src/main/java/ai/labs/eddi/configs/groups/TeamBudgetConfigValidator.java), 400),
  and changeable only by `eddi-admin` while `eddi.groups.team-budget.admin-only-edits=true` (default).
- **Store** — one document per `(groupId, UTC month)` in `team_budget_months` (MongoDB and PostgreSQL twins) holding
  spend, the month's cap adjustment, a bounded adjustment history and the applied transfer ids; atomic `$inc` /
  `ON CONFLICT` spend, one conditional update for a debit, 13-month retention.
- **Metering** — [`TeamSpendMeter`](../../src/main/java/ai/labs/eddi/engine/groups/budget/TeamSpendMeter.java) charges
  the increase of the root discussion's `gc.totalCost` after each member turn, each nested roll-up and each leg. A nested
  child charges nothing (its parent pays). Never fails a discussion; an outage is carried to the next charge.
- **Gate** — a new root discussion, a follow-up or a continuation is refused (409) when the team or the tenant has no
  budget left or the budget cannot be read (fail closed); a cadence fire is skipped, or failed when unreadable.
- **Inside a run** — the team ceiling is written in the discussion's `totalCost` terms
  ([`TeamBudgetCeilings`](../../src/main/java/ai/labs/eddi/engine/groups/budget/TeamBudgetCeilings.java)), so the
  existing `maxCostPerDiscussion` checks enforce it unchanged, with the team's policy and its own transcript text.
- **REST** — snapshot, history, batch listing, set this month's cap, idempotent transfers; reads are VIEW-guarded,
  money moves are `eddi-admin`, both write the audit ledger.
- **Template values** — `{context.teamBudget}` and `{context.teamBudgets}` (a board's `oversight.teams`, filtered by
  the discussion owner's view; numbers only).
- **Metrics** — `eddi_team_budget_{metering,refusals,adjustments}_total`, a Team Budgets dashboard row (the
  Kubernetes copy is synced).

### Design decisions

- The root discussion's group pays; the ledger delta is the charge. The F1 `ISpendSink` feed was evaluated and not
  used: it attributes a nested child's turns to the child's group, carries no root, and fires only for
  tenant-accepted charges, while the in-run ceiling must live in `totalCost` terms.
- The team ceiling excludes its boundary (`Math.nextDown`) so a team at its cap stops instead of taking one more turn.
- One document per team per month (history for a board) rather than the tenant's roll-in-place row.
- 409 for refusals through the existing `GroupDiscussionException` handling; no edits in the hot REST resource.
- Equality exhausts, like the tenant.

### Not done / next

- Deferred to the combined follow-up (`planning/operator-followups/team-budgets.md`): the MCP tools, the Platform
  Operator grants and prompt text, and a `HandoffAdmission` bean that refuses a handoff to a team over budget (after F2).
- The Manager screens (team budget card, quotas table, adjust/transfer dialog) and the LLM budget-transfer proposal
  tool were not built in this change; budgets are managed through REST.
- GDPR erasure does not yet pseudonymise the admin principal recorded in a month's adjustment history (the audit-ledger
  entry carries it as its user id and is covered by the ledger's own erasure).
- No oversubscription warning (sum of team caps above the tenant budget); `limitedBy` shows the tighter limit.

```decision-log
| 2026-11-02 | A team's monthly spend is charged from the root discussion's cost-ledger delta; the root group pays and a nested child charges nothing | The ledger already holds member, nested and group-system spend and records by replacement, so a delta cannot double-count; the per-call spend feed has no root and cannot express the in-run ceiling | Per-call `ISpendSink` events (attribute nested children to the child group, tenant-accepted charges only); reading the tenant counter (stays 0 with quotas disabled) |
| 2026-11-02 | Gates fail closed on an unreadable team budget, metering never fails work | A budget nobody can read is not a budget with room; failing open would drop every cap during an outage | Fail-open at the gate |
| 2026-11-02 | The ceiling a running discussion obeys is expressed in its own `totalCost` and excludes its boundary | Lets the existing ceiling checks enforce it unchanged; equality exhausts, like the tenant | A parallel check in every phase engine |
```
