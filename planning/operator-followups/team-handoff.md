# Follow-up for the combined integration PR: cross-team handoff (F2)

Branch `feat/team-handoff`. Nothing below is in that branch: the Platform Operator and new MCP tools are deliberately deferred to one combined PR after all eight features.

## 1. Platform Operator

Neither `ui/manager/src/lib/operator/system-prompt.ts` nor `tool-scopes.ts` learns anything by itself.

**Prompt.** Explain team handoff:
- two-sided consent: the source lists the target (`langchain.task[].teamHandoff.targets` on an agent, `handoffConfig.targets` on a group) and the target lists the source in `handoffConfig.accept.from` (`agent:<id>` / `group:<id>`, exact ids, no wildcards);
- AUTO vs REVIEW (REVIEW is the default): REVIEW items sit in the intake inbox, which no cadence can pull, until a human with EDIT accepts or declines;
- a group member's handoff cannot be HITL-gated (a gated tool call inside a group turn is auto-rejected), so the intake review is the gate; for a standalone agent `team:handoff_to_team` / `team:*` works as an approval pattern;
- the refusal messages and their causes (not accepted, loop or hop limit, per-run / per-day caps with reset time, inbox full, backlog full, recently declined);
- `eddi.groups.handoff.max-hops` (default 3) and `eddi.groups.handoff.decline-memory` (default `P30D`);
- the backlog cap of 200 and the new `DELETE .../workspace/backlog/{taskId}`.

**Tool scopes.**
- Allow reads: `GET /groupstore/groups/{groupId}/workspace` (already readable) and `GET /groupstore/groups/{groupId}/workspace/handoffs`, so the operator can answer "why didn't Account Desk get the lead?".
- Deliberately **exclude** `POST .../handoffs/{id}/accept`, `POST .../handoffs/{id}/decline` and `DELETE .../backlog/{taskId}`: an intake decision is a human decision, and an operator accepting through its own approval pause would launder one approval through another.
- `handoffConfig` edits are already impossible for the operator (group `PUT` is excluded).

**Revision.** Bump `operator-revision.json` once, in the combined PR (`operator-revision.test.ts` fails until it is bumped).

## 2. MCP tools to add (thin wrappers; no logic)

`TeamHandoffIntake` (package `ai.labs.eddi.engine.runtime.internal.handoff`) is public and has no web types. A wrapper must do the access check itself, at parity with REST (existence first, then VIEW or EDIT on the *group* descriptor), and pass the MCP identity as `userId`.

| Tool | Args | Role tier | Calls |
| --- | --- | --- | --- |
| `list_team_handoffs` | `groupId` | Author (VIEW on the group) | `TeamHandoffIntake.view(groupId)` |
| `accept_team_handoff` | `groupId`, `handoffId`, `subject?`, `description?`, `priority?`, `note?` | Author (EDIT on the group) | `TeamHandoffIntake.accept(groupId, handoffId, new Edits(...), userId)` |
| `decline_team_handoff` | `groupId`, `handoffId`, `reasonCode?`, `note?` | Author (EDIT on the group) | `TeamHandoffIntake.decline(groupId, handoffId, reasonCode, note, userId)` |

Also consider `delete_team_task(groupId, taskId)` (Author, EDIT) calling `TeamHandoffIntake.deleteBacklogTask`.

Register them in `McpToolFilter` next to `add_team_task`, bump the hard-coded count in `McpToolContractTest`, update `McpRoleTierParityTest`, `docs/mcp-server.md` (next to `add_team_task`), and check `integrations/claude-plugin/` skills (`SkillToolReferencesTest`). `list_team_backlog` does not read-repair and could expose `origin`; `list_team_handoffs` already carries origins.

There is intentionally no MCP tool to *file* a handoff: a human with EDIT uses `add_team_task`; handoff is an agent-to-team path.

## 3. Other features that plug in

- **Budgets (F6):** register a `HandoffAdmission` bean to refuse intake when the receiving team's budget is exhausted. The cost of working a handed-off task already lands in the receiving workspace's `metrics.totalCost`.
- **Guardrails (F5):** a `HandoffAdmission` is the seam where the same screen applies to the tool and to `onDecision` rules (decision rules are not tool calls). `team:handoff_to_team` is the stable name and source for argument guardrails.
- **Edit-on-approve (F3):** intake accept/decline already writes `approvalCategory=team_handoff_intake` with `APPROVED | APPROVED_EDITED | REJECTED`; the audit write is in `TeamHandoffIntake.auditDecision`, one place to route through F3's shared recorder.
- **Approval cards (F7):** `TeamHandoffService.describeTarget(targetGroupId, sourceKey)` is a read-only summary (accepting, mode, inbox and backlog size) for a `handoff_to_team` pause.
