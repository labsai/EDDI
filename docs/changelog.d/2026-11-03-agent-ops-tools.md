## ✨ feat(llm): agent operations tools — team scorecard, budget proposal, follow-up scheduling (2026-11-03)

**Repo:** EDDI (`feat/agent-ops-tools`, branched from the local `feat/operator-mcp-integration`, which merges the still-open PRs #1222–#1231)

### What changed and why

A standing team could not read its own spend or approval numbers, propose a budget move, or book its own check-back,
so a person pasted metrics and moved money by hand. Three built-in tools now do that for an agent, each **off unless
named in `builtInToolsWhitelist`** (an omitted whitelist does not admit them), see [Agent Operations Tools](../agent-ops-tools.md):

- `read_team_scorecard` (slug `team_scorecard`) — read-only: month spend and budget left, tasks done/failed per UTC
  week, approval outcomes with reason codes, retro lessons, for the agent's own group, its `oversight.teams` and
  `agentOps.scorecard.groupIds`. Each team is read only if the conversation's user may VIEW it (`ResourceAccessGuard`,
  the REST rule), checked per call and failing closed; no `userId` parameter.
- `propose_team_budget_raise` / `propose_team_budget_transfer` (slug `propose_team_budget`) — assembled only when the
  task's effective `toolApprovals` requires approval for both tools, so the existing approval card (Manager, Slack,
  e-mail, MCP) is the human gate. The body re-checks that the approving caller is an `eddi-admin` and refuses
  otherwise (owner/`eddi-approver`, timeout auto-approval and autonomy exemptions apply nothing). Applies through
  `TeamBudgetService`; per-turn, per-day (counted from the adjustment history) and amount caps; idempotent.
- `schedule_followup` / `list_followups` / `cancel_followup` (slug `schedule_followup`) — one-shot schedules for the
  agent's own conversation or a fresh one; horizon ≤ 90 days, per-agent pending and per-turn caps, a mandatory
  `maxCostPerFire`, never admin-powered, an agent sees and cancels only its own.

New `agentOps` block on an LLM task (limits only, validated at save time). `AgentOpsToolsProvider` is a CDI bean
wired into `AgentOrchestrator`. `RetroEngine.RETRO_SOURCE` became public. Manager: an **Agent Operations Tools**
section in the LLM editor, strings in all 11 locales. Platform Operator: the team-budget guidance now covers the
tools (revision bump).

### Design decisions

- Gate-based approval rather than a new pending-proposal store: reuses the approval card, Slack channel, audit and
  crash recovery, at the cost of the approver having to be an admin for the change to apply.
- The budget tool refuses to exist without a gate instead of trusting the designer to add one.

### Tests

Plain JUnit 5 + Mockito for the three tools, the provider and the validator; Vitest for the Manager helpers.

### Review follow-ups (PR #1259)

- `schedule_followup` reserves its per-turn slot only when a booking is about to be written and releases it on a
  pending-cap refusal or store failure, so a malformed call no longer locks the model out for the turn.
- `pending()` pages through all of the agent's schedules (bounded at 40 pages of 500) before filtering, so a
  check-back past the first page still counts, lists and cancels.
- The scorecard's approval window now starts at midnight UTC on the Monday of the oldest throughput week shown.
- Manager: unticking the last whitelisted tool switches built-ins off instead of leaving an empty whitelist that
  means "all built-in tools"; the limit blocks use the logical `ms-5` for RTL.
- Docs: e-mail is a notification, not an approval channel; the task-level `toolApprovals` shape is shown; a group
  member's gated call is auto-rejected, so budget proposals belong to a standalone oversight agent; `maxPending` is
  per agent and booking user. The Platform Operator prompt says the same about group turns and `maxPending`
  (revision 55). Inline Mockito FQNs in two tests replaced by static imports.
