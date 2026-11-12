# Team Budgets

A [group](group-conversations.md) can carry a **monthly dollar budget**. It is the team's share of the
tenant's [monthly cost budget](tenant-quotas.md#the-monthly-cost-budget): when it is spent, the team starts no
new work until the month turns over or an administrator moves money. Without a `teamBudget` nothing changes:
no gate, no ceiling, and no metering either: a group that does not configure the block writes nothing to the
budget store and its hot path gains nothing. Only a group that sets the block is metered, from the moment it does
(spend before that is not back-filled). `eddi.groups.team-budget.metering-enabled=false` switches the whole
feature off globally.

Use it when several standing teams share one deployment and one tenant budget, and one busy team must not eat
another's month: a Content Studio at $35, a Signal Radar at $30, a Growth Board over both.

## Configuration

```json
{
  "name": "Content Studio",
  "teamBudget": {
    "monthlyBudgetUsd": 35.0,
    "onExhausted": "SYNTHESIZE_NOW",
    "oversight": { "teams": ["<signalRadarGroupId>"] }
  }
}
```

| Field | Default | Meaning |
|---|---|---|
| `monthlyBudgetUsd` | required | The base cap per calendar month. Positive and finite; anything else is a 400 at save time |
| `onExhausted` | the group's `protocol.onCostExceeded` (`SYNTHESIZE_NOW`) | What a *running* discussion does when the team cap binds: `SYNTHESIZE_NOW` or `ABORT` |
| `oversight.teams` | none | Other groups whose budget this group's members may read, for a "board" (at most 50, unique, not the group itself) |

The base lives in the group configuration, so it is versioned, exported and imported with the group. A raise
or a transfer *during* a month is runtime state in its own store and never mints a configuration version; the
next month starts at the base again.

## The window

The month is the **UTC calendar month**, the same window as the tenant's monthly budget. The effective cap is
`base (the group's current configuration) + this month's adjustment`, so editing the base mid-month keeps an
earlier transfer. A month's document is kept for 13 months (`eddi.groups.team-budget.retention-months`), which
is what the history endpoint reads.

## Who pays

**The root discussion's group.** A nested child discussion (a group that is a member of another group) charges
nothing itself: its spend already rolls into its parent's cost ledger, so charging both would pay twice. A task
handed to another team is paid by the receiving team, because that team's own cadence is the root.

Ad-hoc discussions count against the team as much as cadence runs do: the budget is per team, not per
cadence. Follow-ups and continuations spend on the same discussion and count too.

**What is charged.** The increase of the discussion's `totalCost`, the sum the
[cost ledger](group-conversations.md) already keeps (member conversations, nested children, and the group's own
summaries, stance and judge calls). Reading the ledger rather than adding up per-call events means it cannot
double-count. It is charged after every member turn, after a nested group returns and at the end of each leg.

The per-call spend feed that [Model Pricing](model-pricing.md#for-other-features-spend-attribution) offers
(`ISpendSink`) is deliberately **not** used for this: it attributes a nested child's turns to the child's own
group, fires only for charges the tenant counter accepted, and carries deltas, while the ceiling below has to be
expressed in the discussion's own `totalCost`.

Only **priced** spend counts, exactly as for the tenant: a model with no price on its configuration or in the
[price catalog](model-pricing.md) costs $0 here too. Metering never fails a discussion. If the budget store is
down, the amount is held back and charged with the next charge.

## What happens at the cap

| Where | What |
|---|---|
| A new discussion (`discuss`, async start, a cadence-created one) | Refused before anything is created. REST: **409** with the reason as text; the streaming endpoint sends the same text in its `group_error` event |
| A follow-up or continuation of an existing root discussion | Refused the same way (**409** with the reason; a streamed continuation sends it in `group_error`). A follow-up's member turn is charged to the team as soon as it ends, and it is refused before it starts when the discussion has already reached its team ceiling |
| A [cross-team handoff](team-handoff.md) **into** a team that is exhausted (or whose budget cannot be read) | Refused with the same text, before anything is filed: the model and the handoff audit entry get the reason. `surface="handoff"` on the refusal metric |
| A standing-team cadence fire | **Skipped**, not failed: a success that is not retried, with the backlog untouched. The skip text says the budget is spent |
| A discussion already running | Runs on. It is *not* cut short by a gate; its own ceiling (below) handles it |
| A resume after an HITL pause or a human input | Not gated: work already started is not abandoned |

Exhaustion is **equality**: a team that has spent exactly its cap is exhausted, as the tenant is.

The refusal text names the team, the numbers, the reset instant and what an administrator can do, for example:
`Team 'Content Studio' has spent $35.02 of its $35.00 budget for 2026-11; it resets 2026-12-01T00:00:00Z. An
eddi-admin can raise this month's cap (PUT /groupstore/groups/<id>/budget/current-month) or transfer budget
from another team.`

### Inside a running discussion

At the start of every leg the discussion computes a **team ceiling** in its own `totalCost` terms:

```
ceiling = charged + max(0, min(cap - teamSpent, tenantRemaining))   (the boundary itself is excluded)
```

and refreshes it after every charge from the team total the store returns. The group's existing cost-ceiling
machinery (`maxCostPerDiscussion`) enforces it unchanged, through the same checks and the same transcript
entry, so every phase and task-force engine honours it without knowing teams exist. The binding ceiling is the
tighter of the group's own and the team's; on a tie the group's own protocol wins. A follow-up with one member
is a leg too: it is refused when the run is already past the team ceiling, and its cost is charged to the team
like a phase turn's.

When the team ceiling is the one that binds, the policy is `teamBudget.onExhausted` (default: the group's
`onCostExceeded`). `SYNTHESIZE_NOW` keeps its synthesis exemption, so the run still delivers an answer. The
transcript entry reads `Team budget reached (2026-11): this run has used $X of the $Y its team could absorb —
SYNTHESIZE_NOW` (`Tenant budget reached` when the tenant's remainder was the tighter one). Nested groups inherit the
combined remainder.

Two bounds to know about:

- **Overshoot** is at least one in-flight turn per concurrent discussion, as for `maxCostPerDiscussion`. Under
  `SYNTHESIZE_NOW` add one synthesis phase, which is exempt from the ceiling; under `ABORT` the discussion fails at
  the ceiling and no synthesis phase runs. It is more where several turns are dispatched under one check: a `PARALLEL` phase checks
  the ceiling once and then runs every speaker, and a task-force wave runs all its tasks before the next check. So
  one discussion can overshoot by a whole parallel phase or wave; size the cap with that margin when a team uses
  them. Two discussions of the same team running at once each learn of the other's spend at their next charge.
- The cap and the tenant remainder are read at the start of a leg. An administrator raising the cap during a
  run takes effect from the next leg; the run's ceiling stays self-decrementing in between, so exposure is bounded.

If the tenant's budget is what ran out, the synthesis turn is itself refused by the tenant's gate and serves its
fallback answer. The team ceiling ends the run early instead of letting members each serve fallbacks.

## When the budget cannot be read

| Surface | Behaviour |
|---|---|
| Starting new work, follow-up, continuation | **Fail closed**: refused, reason `unavailable`. A budget nobody can read is not a budget with room |
| A cadence fire | Fails (not skipped), so the retry and dead-letter machinery makes it visible |
| Charging spend | Never fails anything. The amount is carried and sent with the next charge |
| A discussion already running | Continues under its last-known ceiling, which keeps decrementing with its own spend |

Failing open at the gate was rejected: a sustained database outage would remove every team cap at once, which
is exactly when nobody is watching the spend.

## REST

| Method | Path | Role | |
|---|---|---|---|
| GET | `/groupstore/groups/{groupId}/budget` | admin, editor, viewer; VIEW on the group | Cap, spent, remaining, reset, `limitedBy` (`TEAM`, `TENANT`, `NONE`) |
| GET | `/groupstore/groups/{groupId}/budget/history?months=6` | same | Past months with their adjustments (actor, reason) |
| GET | `/administration/team-budgets?groupIds=a,b,c` | same; groups the caller cannot view are left out | Several snapshots and the tenant's remainder (at most 50 ids) |
| PUT | `/groupstore/groups/{groupId}/budget/current-month` | admin | `{"capUsd": 50, "reason": "launch week"}`; not below what is spent |
| POST | `/administration/team-budgets/transfers` | admin | `{"fromGroupId", "toGroupId", "amountUsd", "reason"}` plus an `Idempotency-Key` header |

Setting the cap writes the cap, not a difference: when two administrators (or an administrator and an approved
proposal) set this month's cap at the same moment, the one that lands second ends at exactly the cap it asked for,
and its audit entry names the cap it replaced. A set re-reads and tries again when the cap changed under it; after
three such attempts it gives up with **503**, and nothing was changed by that request.

A transfer debits the source first, conditional on the source keeping a cap at or above its spend, then credits
the target; if the credit fails the debit is reversed (an attempted reversal, recorded as `TRANSFER_OUT_REVERSED`
and audited as `REVERSED`). If the reversal fails too, the audit entry says `PARTIAL`: the source was debited and the
target never credited, so money has disappeared from caps and an administrator should reconcile it by raising the
source's cap again. Money never appears. Retrying with the same `Idempotency-Key` moves nothing twice: a retry of a
finished transfer returns the original result without a second audit entry; a retry of one that was reversed is
refused (use a new key); a retry of one that stopped between debit and credit completes the credit. A key is
bound to its request: reusing it with other teams or another amount is refused (400) instead of crediting what
the first request never debited. The key is remembered for the last 200 adjustments of a team-month, so a retry
that arrives after more than 200 later adjustments of the same team and month is no longer recognised. It is also
looked up in the **current UTC month only**: a retry sent after the month has turned is a new transfer in the new
month, even if the first one completed. Check the previous month's history before retrying across the boundary. A
reason is
required (at most 1000 characters). Both adjustments and transfers write an audit-ledger entry
(`ai.labs.budget`, `TEAM_BUDGET_ADJUSTED` / `TEAM_BUDGET_TRANSFERRED`, with amount, month and reason), because the
admin-action audit records only method, path and status.

## Who may change what

- Reading a team's budget needs VIEW on the group.
- Every operation that moves money is `eddi-admin`, as tenant quotas are.
- Changing a group's `teamBudget` (adding, raising, lowering, removing it) needs `eddi-admin` while
  `eddi.groups.team-budget.admin-only-edits` is `true` (the default). Otherwise an editor could raise their own
  team's cap by saving the group.
- Whoever saves an `oversight.teams` list must hold VIEW on every group in it.

## Template values (a board)

Every member turn of a group with a `teamBudget` can use:

| Value | Content |
|---|---|
| `{context.teamBudget}` | The team's own budget: `name`, `month`, `capUsd`, `spentUsd`, `remainingUsd`, `exhausted`, `limitedBy`, `resetsAt` |
| `{context.teamBudgets}` | A list of the same for every `oversight.teams` entry the discussion owner may view |

A listed team the owner cannot view (or that no longer exists) is left out, so a board never shows a number its
author could not have read. Transcripts, adjustment actors and reasons are never exposed this way: a reason is
free text and appears only on the REST history, behind VIEW. Values are cached per discussion for 30 seconds, so
a turn costs no extra I/O. They also need `eddi://ai.labs.templating` as the last workflow step, like any
`{…}` placeholder. If the budget cannot be read the values are absent, not zero.

## Metrics

`eddi_team_budget_metering_total{outcome}`, `eddi_team_budget_refusals_total{surface,reason}` and
`eddi_team_budget_adjustments_total{kind}`; see [Metrics](metrics.md#team-budget-metrics). They are on the Full
Metrics Reference dashboard under **Team Budgets**.

## Limits worth knowing

- **Only priced spend counts.** Unpriced models cost $0, so a team made only of unpriced agents never reaches its cap.
- **Upgrade mid-month.** Spend recorded before a group had a budget is not charged to that team: a discussion
  that was running when the budget was set is charged only what it spends from its next leg on.
- **A crash** between the store write and the save of the discussion can charge one amount again after a resume;
  the tenant meter has the same bound.
- **Oversubscription is not checked.** Team caps may add up to more than the tenant's budget; the tenant gate
  still binds, and `limitedBy` says which limit is the tighter one.
- **MCP and the Platform Operator.** `read_team_budget`, `list_team_budgets` and, for administrators,
  `adjust_team_budget` and `transfer_team_budget` (see [MCP Server](mcp-server.md#team-budget-tools-4)). The Platform
  Operator reads budgets and explains refusals; it holds no budget write, so a cap is raised by an administrator.

## In the Manager

- The group's workspace page shows a **team budget card** (cap, spent, remaining, reset date, a History table, and a
  badge when the budget is spent or the tenant is the tighter limit). It is absent for a group without a
  `teamBudget`.
- **Quotas** lists the teams that have a budget, with a bar per team, among the first 50 groups of the group list
  (the batch endpoint's limit). A budgeted team beyond those is not in the table; read it on its workspace page or
  with `GET /groupstore/groups/{groupId}/budget`. If the budgets cannot be read, the table says so and offers a
  retry.
- An administrator gets **Adjust this month** (cap and a required reason; refused below the spend) and **Transfer
  budget** (from, to, amount, reason; one `Idempotency-Key` per opening of the dialog, replaced after a 4xx answer,
  which applied nothing, and kept after a 5xx or network error, so the retry stays idempotent). Other roles do not see the
  buttons; the server enforces it regardless.

## Agents proposing budget changes

An agent can read its team's numbers and propose a raise or transfer that an administrator approves, with the
[Agent Operations Tools](agent-ops-tools.md). The change still goes through the admin-only operations above.
