# Operator, MCP and wiring follow-up — team budgets (`feat/team-budgets`)

For the combined "F9 integration" PR. Nothing here was built on the feature branch.

## Wiring after F2 (team handoff) is merged

- Register a `HandoffAdmission` bean (F2's SPI) that refuses a handoff when the **target** team is over budget:
  `TeamBudgetService.decide(targetGroupId)` returns `Decision(allowed, reason, message, snapshot)`; refuse on
  `!allowed()` with `message()`. `reason` is `exhausted`, `tenant` or `unavailable` (fail closed, as the gate does).
  A handed-off task is paid by the receiving team with no further change, since that team's cadence is the root
  discussion. The snapshot (`TeamBudgetService.snapshot(groupId)`) can also tell the sender "the receiving team has
  $0 left this month; the task will wait".
- Expect a merge conflict in `GroupWorkspace.java` only if F2 and this branch both touch it; this branch does not.

## Platform Operator

### Allow-list (`ui/manager/src/lib/operator/tool-scopes.ts`), read-only

- `GET /groupstore/groups/{groupId}/budget`
- `GET /groupstore/groups/{groupId}/budget/history`
- `GET /administration/team-budgets` (query `groupIds`)

**Do not grant** `PUT /groupstore/groups/{groupId}/budget/current-month` or `POST /administration/team-budgets/transfers`:
money mutations stay with the admin, as with `GET /administration/quotas` only. Check that request bodies are `application/json`.

### System prompt (`ui/manager/src/lib/operator/system-prompt.ts`)

Teach the operator:

- `teamBudget` fields and defaults (`monthlyBudgetUsd`, `onExhausted`, `oversight.teams`); absent means no team cap.
- The window is the UTC calendar month; the effective cap is the base plus this month's adjustment.
- The root discussion's group pays, ad-hoc discussions and follow-ups included; nested children charge their parent.
- Refusal texts: 409 `Team '…' has spent $x of its $y budget for YYYY-MM; it resets …`, reason `unavailable` when the
  budget cannot be read (fail closed); cadence skip reasons carry the same text; an unreadable budget fails the fire.
- A running discussion stops at the team ceiling: transcript entry `Team budget reached (YYYY-MM): …`, policy
  `onExhausted` (default the group's `onCostExceeded`, `SYNTHESIZE_NOW`).
- The tenant and team limits both apply (`limitedBy`); with quotas disabled the tenant counter is 0.
- Only an `eddi-admin` raises a cap or transfers; give the exact REST call (`PUT …/budget/current-month` with
  `{capUsd, reason}`, `POST /administration/team-budgets/transfers`) or the future MCP tool.
- Only priced spend counts; metering never fails a discussion; metric names
  `eddi_team_budget_metering_total`, `eddi_team_budget_refusals_total`, `eddi_team_budget_adjustments_total`.
- Changing `teamBudget` on a group needs `eddi-admin` (`eddi.groups.team-budget.admin-only-edits`).

### Revision

Bump `ui/manager/src/lib/operator/operator-revision.json` with these changes.

## MCP tools (deferred)

New class `engine/mcp/McpTeamBudgetTools` (do not grow `McpGroupTools` or `McpAdminTools`); add the names to
`McpToolFilter`, bump the `McpToolContractTest` count, update `docs/mcp-server.md`, and check
`integrations/claude-plugin/` skills (`SkillToolReferencesTest`).

| Tool | Args | Role tier | Calls |
|---|---|---|---|
| `read_team_budget` | `groupId` | author tier plus viewer (needs a constant beside `McpRoles.OBSERVE`); VIEW on the group | `TeamBudgetService.snapshot(groupId)` |
| `list_team_budgets` | `groupIds` (comma separated, optional history none) | same | `TeamBudgetService.snapshot(...)` per id the identity may view, as `RestTeamBudgets.listBudgets` does |
| `adjust_team_budget` | `groupId`, `capUsd`, `reason` | `ADMIN_ONLY` | `TeamBudgetService.setCurrentMonthCap(groupId, capUsd, reason, actor)` |
| `transfer_team_budget` | `fromGroupId`, `toGroupId`, `amountUsd`, `reason`, optional `idempotencyKey` | `ADMIN_ONLY` | `TeamBudgetService.transfer(from, to, amountUsd, reason, actor, key)` |

All four service methods are public and REST-backed; the MCP classes are thin wrappers.

## Not built here (descoped, see the changelog fragment)

- The LLM budget-transfer proposal tool (`proposeBudgetTransfer`, inert until an admin approves; a
  `team_budget_proposals` store; `TeamBudgetToolsProvider` inserted directly after `ArtifactToolsProvider` in
  `AgentOrchestrator`). Group member tool calls cannot use the HITL gate (`inGroupTurns: REJECT`), which is why it
  must be an inert proposal rather than a gated mutation.
- GDPR pseudonymisation of the actor in a month's adjustment history.
- Oversubscription warning (sum of team caps vs the tenant budget) on the listing.
- Per-group budget gauges (`cap`/`spent`/`remaining`): they need a periodic read of every budgeted group.
