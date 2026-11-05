## ✨ feat(groups): native cross-team handoff (2026-11-02)

**Repo:** EDDI (`feat/team-handoff`)

### What changed and why

A standing team could only receive work from a human typing into its backlog. Cross-team handoff lets another team, or a standalone agent, file a task into a team's workspace, under **two-sided consent enforced by code**: the source lists the target (`teamHandoff.targets` on an LLM task, `handoffConfig.targets` on a group) and the target lists the source (`handoffConfig.accept.from`, `agent:<id>` or `group:<id>`, exact ids). Nothing else changes by default: every new field is absent-means-off, and old stored or imported configs and workspaces behave exactly as before.

- **One pipeline.** `TeamHandoffService.file` is the only code that writes to another team's workspace. It checks consent against the target's *current* config, refuses self-handoff, cycles and the hop limit (`eddi.groups.handoff.max-hops`, default 3), cleans the text, reserves a per-run and two per-UTC-day slots (the `toolQuotas` store, so atomic across pods), asks any `HandoffAdmission` bean, then writes through the workspace compare-and-set, re-validating duplicates, decline tombstones and capacity on every retry. A refused attempt gives its slots back.
- **Two entry points.** A `handoff_to_team` tool whose `team` argument is an enum of the aliases the source declared (new approval source `team`), and deterministic `handoffConfig.onDecision` rules that file a task when a discussion completes with a matching `VOTE`/`VERDICT`/`AGREEMENT`/`AWARD`, with an idempotent dedupe key per discussion, round and rule.
- **AUTO vs REVIEW.** REVIEW (the default) puts the item in an intake inbox that is in no task list, so a cadence can never pull it; a human with EDIT accepts (optionally edited) or declines it. Both write an audit entry in the shared approval vocabulary (`team_handoff_intake`, `APPROVED | APPROVED_EDITED | REJECTED`, `reasonCode`).
- **Lineage and provenance.** The origin of a handed-off task lives in a side map keyed by task id, and the cadence stamps the lineage of the tasks it pulled onto the discussion, so A to B to A is caught across discussions. A handed-off task is marked in the cadence question as a request to evaluate, not instructions.
- **New REST** under `/groupstore/groups/{groupId}/workspace`: `GET /handoffs`, `POST /handoffs/{id}/accept`, `POST /handoffs/{id}/decline`, and `DELETE /backlog/{taskId}` (the 200-task backlog had no way to shrink; handoff makes the ceiling reachable). Manager: an Intake section and an origin badge on the workspace page, a handoff section in the group advanced editor.
- `team` is added to the known HITL tool sources and to the tool-cache side-effecting sources (a cached result must never stand in for a write). SSE gets a `team_handoff` event; metrics `eddi_team_handoff_*`.

Not built here: new MCP tools for the intake (the service methods are public and web-free for thin wrappers), and the Platform Operator update, both listed in `planning/operator-followups/team-handoff.md`.

**Rolling upgrade caveat:** an older pod that re-saves a workspace drops the unknown `handoffs` field, losing pending inbox items and origins (same as `reworkCounts`); the audit entries hold the subject and source for recovery.

**Files:** [`TeamHandoffService.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/handoff/TeamHandoffService.java), [`TeamHandoffIntake.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/handoff/TeamHandoffIntake.java), [`HandoffPolicies.java`](../../src/main/java/ai/labs/eddi/configs/groups/model/HandoffPolicies.java), [`WorkspaceHandoffs.java`](../../src/main/java/ai/labs/eddi/configs/groups/model/WorkspaceHandoffs.java), [`TeamHandoffToolsProvider.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/TeamHandoffToolsProvider.java), [`RestTeamHandoffs.java`](../../src/main/java/ai/labs/eddi/configs/groups/rest/RestTeamHandoffs.java), [`team-handoff.md`](../team-handoff.md)

```decision-log
| 2026-11-02 | Cross-team handoff requires two-sided consent (source `targets` and target `accept.from`), enforced in `TeamHandoffService`, not by prompt | A one-sided design lets an author with EDIT on any agent write into any team's backlog | Source-only allow-list; wildcard accept rules (explicit ids keep consent auditable) |
| 2026-11-02 | Handoff provenance is a side map on the workspace (`handoffs.origins`), not a `TaskItem` component | The cadence writeback rebuilds tasks with the shorter constructors and already drops `createdByAgentId`; a component would also touch ~30 call sites | Adding `origin` to the `TaskItem` record |
| 2026-11-02 | The default accept mode is REVIEW (an intake inbox no cadence can pull) | The handed-over text crosses a trust boundary and will be read by the receiving team's models | AUTO by default |
| 2026-11-02 | The approval source for the tool is a new `team`, not `builtin` | `exempt: ["builtin:*"]` would silently un-gate cross-team writes | Reusing `builtin` |
```
