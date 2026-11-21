# Agent Operations Tools

Three built-in tools that let an agent run part of its own team's operations instead of waiting for a person to
paste numbers or click a button: a read-only **team scorecard**, a **budget proposal** a human administrator
approves, and **follow-up scheduling**. They are meant for standing teams (see [Team Budgets](team-budgets.md),
[Group Conversations](group-conversations.md)) and an oversight agent that reports on them.

Each tool is **off unless it is named in the LLM task's `builtInToolsWhitelist`** (and `enableBuiltInTools` is on).
Unlike the plain built-ins, an omitted whitelist ("all built-in tools") does *not* admit them: two of the three
spend or move money, and the third books future work.

| Slug in `builtInToolsWhitelist` | Tools | What it does |
|---|---|---|
| `team_scorecard` | `read_team_scorecard` | Read-only numbers for the agent's team(s) |
| `propose_team_budget` | `propose_team_budget_raise`, `propose_team_budget_transfer` | A budget change applied only after an admin approves |
| `schedule_followup` | `schedule_followup`, `list_followups`, `cancel_followup` | One-off check-backs for the agent itself |

```json
{
  "enableBuiltInTools": true,
  "builtInToolsWhitelist": ["team_scorecard", "propose_team_budget", "schedule_followup"],
  "agentOps": {
    "scorecard": { "weeks": 4, "maxLessons": 5, "groupIds": ["<growthBoardGroupId>"] },
    "budgetProposal": { "maxPerTurn": 1, "maxPerDay": 3, "maxAmountUsd": 25 },
    "followUp": { "maxPending": 5, "maxPerTurn": 2, "maxHorizonDays": 30, "maxCostPerFireUsd": 0.5 }
  }
}
```

`agentOps` is optional and every field has the default shown. It is validated at save time: every bound is refused
with the field path rather than clamped. The accepted ranges: `scorecard.weeks` 1–12, `scorecard.maxLessons` 0–20,
`scorecard.groupIds` at most 20 distinct, non-blank ids; `budgetProposal.maxPerTurn` 1–5, `budgetProposal.maxPerDay`
1–50; `followUp.maxPending` 1–50, `followUp.maxPerTurn` 1–10, `followUp.maxHorizonDays` 1–90; the two dollar
amounts must be positive. In the Manager it is the **Agent Operations Tools** section of the LLM
editor. Ticking a tool there keeps the agent's other built-ins (an empty whitelist is converted to the full list plus
the new slug, so it never narrows the agent to one tool).

Nothing here takes a `userId`: who is asking is the conversation's user, as for every in-conversation tool.

## Team scorecard

`read_team_scorecard(groupId?)` returns, per team: the month's budget (cap, spent, remaining, `limitedBy`, reset),
backlog throughput per UTC week (done and failed, empty weeks listed as zeros) with the open task count, approval
outcomes over the window (approved, approved with edits, rejected, rates, top reason codes) and the most recent
retro lessons.

**Which teams.** The agent's own group (the group of the discussion it is running in), the teams in that group's
`teamBudget.oversight.teams`, and the group ids listed in `agentOps.scorecard.groupIds` (for an oversight agent that is
not inside a discussion). The discussion membership is checked, not just the id the client sent.

**Access.** Every team is read only if the conversation's user may VIEW it, checked at call time with the same rule
the REST budget and workspace reads use (`ResourceAccessGuard`). A team outside the scope and a team the user may not
view get the same answer, so the tool cannot be used to probe for group ids. A check that errors is a "no".

Each section is read on its own: a store that is down shows `"status": "unavailable"` for that section rather than a
zero, and the others still answer. At most 10 teams are returned per call.

## Budget proposal

`propose_team_budget_raise(groupId, newCapUsd, reason)` and
`propose_team_budget_transfer(fromGroupId, toGroupId, amountUsd, reason)`.

**A human approves; nothing applies on the model's say-so.** The approval is the ordinary
[tool-call approval](hitl.md#tool-level-approval-gating) — decided on the same card in the Manager, in Slack or over
MCP (an [e-mail notification](hitl.md#email-notifications) only says that something is waiting and links to the
Manager; it approves nothing) — so the tool is **assembled only when the task's effective `toolApprovals` requires
approval for both tools**. At agent level that is `hitlConfig.toolApprovals`:

```json
{ "hitlConfig": { "toolApprovals": { "requireApproval": ["propose_team_budget_*"] } } }
```

and on the LLM task itself the same block sits directly under `toolApprovals` (no `hitlConfig` wrapper):

```json
{ "toolApprovals": { "requireApproval": ["propose_team_budget_*"] } }
```

Do not exempt them. Without the gate the agent has no budget tool at all and the engine logs a warning; so does
turning tool approvals off cluster-wide (`eddi.hitl.tool.enabled=false`).

**Not from inside a group discussion.** A group member's gated tool call is
[auto-rejected](hitl.md#group-members) (`system:group`): it never reaches an approver, so a member agent proposing a
budget change during a discussion turn is always told no. Propose budget changes from a standalone oversight agent —
an ordinary conversation that reads the teams through `agentOps.scorecard.groupIds` — and let the discussion's
members use the read-only scorecard.

**Only an `eddi-admin`'s approval applies.** Moving budget is admin-only on REST and MCP, and an approval by the
conversation's owner or an `eddi-approver` must not widen that. The approved call runs as its approver; if that is not
an administrator (or there is no approver at all, as with a timeout auto-approval or an earned-autonomy exemption)
nothing changes and the model is told why. An unapproved call learns nothing about any budget.

The change goes through the same service as the REST operations (`TeamBudgetService`): the cap cannot go below what
is spent, a transfer debits then credits and is reversed if the credit fails, and the audit ledger entry names the
approving admin as actor. The adjustment history reason starts with `[agent:<agentId>]`.

| Limit (`agentOps.budgetProposal`) | Default | Meaning |
|---|---|---|
| `maxPerTurn` | 1 | Calls one turn may make |
| `maxPerDay` | 3 | Changes applied for this agent in a rolling 24 hours, counted from the budget's adjustment history, so a restart does not reset it |
| `maxAmountUsd` | 25 | The most one proposal may add to a cap or move |

The agent can only name teams in its scorecard scope that the user may view. **Idempotent:** a raise names the cap it
wants, so applying it twice is a no-op; a transfer's idempotency key is derived from agent, conversation, teams,
amount, reason and month, so a replay moves nothing twice.

## Follow-up scheduling

`schedule_followup(at, message, target?)` books a one-shot schedule (the same mechanism and fire executor as
[Scheduling](scheduling.md), including fire logs, retries and dead-lettering). `target` is `conversation` (default:
the fire continues this conversation) or `agent` (a fresh conversation with the same agent); inside a group
discussion only `agent` is offered. `list_followups` shows the agent's waiting check-backs, `cancel_followup` removes
one.

| Limit (`agentOps.followUp`) | Default | Meaning |
|---|---|---|
| `maxPending` | 5 | Check-backs waiting at once, per agent and booking user (each user of the agent has their own allowance) |
| `maxPerTurn` | 2 | Bookings one turn may make |
| `maxHorizonDays` | 30 (never above 90) | How far ahead `at` may be; at least a minute ahead |
| `maxCostPerFireUsd` | 0.5 | Becomes the schedule's `maxCostPerFire`; a booked fire is never unlimited |

A check-back runs as the user who booked it and never carries administrator rights; the fire re-checks that user's
access to the agent like any schedule. Schedules carry `metadata.followupType = agent_followup`; an agent lists and
cancels only its own (an id that belongs to someone else answers like an unknown one). Requires priced models for
the cost ceiling to bite, as for `maxCostPerFire` generally.

## For the Platform Operator

The Platform Operator knows these tools (its team-budget guidance covers the slugs, limits, the approval requirement
and the admin-only rule); it cannot approve a budget change.
