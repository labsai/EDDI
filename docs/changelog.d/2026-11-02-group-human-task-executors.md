## ✨ feat(groups): HUMAN members execute task-force tasks (2026-11-02)

**Repo:** EDDI (`feat/group-human-task-executors`, stacked on `feat/group-human-tiebreak`)

### What changed and why

I6 shipped human group members but refused them in any group with PLAN/EXECUTE/VERIFY phases, because task waves
assign and time work on agent latencies. A HUMAN member may now **execute** the tasks assigned to them; planning
and verifying stay with agents.

- **Engine** — the EXECUTE wave loop (`TaskForceEngine`) filters HUMAN-assigned tasks out of its waves. When no
  agent task is left but a human task is executable, it throws the existing `HumanTurnRequired` for the
  highest-priority one ([`GroupHumanTasks`](../../src/main/java/ai/labs/eddi/engine/internal/groups/GroupHumanTasks.java)),
  now carrying a `HumanRequestDetail` of kind `TASK`, which the phase dispatch commits as the usual I6 pause.
  `GroupHitlCoordinator.resolveHumanTurn` applies the answer to the task inside the same compare-and-swap
  (complete, or submit for approval under TASK-granularity HITL; a `SKIP_TURN` timeout fails it) and puts it back
  on a rolled-back resume; the resumed wave loop runs its dependents. A human task is a turn and costs $0. A VERIFY
  rework re-runs EXECUTE with human pauses disabled and skips HUMAN-assigned tasks (they stay FAILED, with a note).
- **Assignment** — round-robin never picks a HUMAN member (`TaskListParser.roundRobinAssign` and
  `TaskForceEngine.resolveAssignee("ALL")`); explicit `ROLE:` / principal assignment, agent-filed tasks and the
  planner (told which members are human) still can. Bidders were already agents only.
- **Save time** — `AgentGroupStore.humanMemberProblems` no longer refuses task phases; it calls
  `GroupGapsValidation.humanTaskForceProblems`, which refuses a HUMAN planner/verifier (the moderator, or the first
  member by speaking order without one) in a group with PLAN or VERIFY phases. `targetEachPeer` phases and nested
  groups containing humans stay refused.
- **Surfaces** — the summaries' `pendingKind: TASK` / `pendingTaskId` (from the tie-break PR), a Slack task notice,
  and in the Manager a task variant of the human-turn banner (subject and description) plus a **Task** inbox badge;
  new i18n namespace `groupHumanTasks` (11 locales).
- **Docs** — [Group Human Decisions](../group-human-decisions.md) gains *Humans as task-force executors*;
  [Group Conversations](../group-conversations.md) points there from the human-member bounds and the TASK_FORCE
  section.

### Not done

- The save-time warning for a standing-team cadence whose `eddi.groups.cadence.claim-ttl` is shorter than the human
  turn timeout: the TTL is a deployment property the group store cannot see. The docs say to keep `turnTimeout` well
  inside it.
- Reassigning an unanswered human task to an agent, and humans as bidders.

### What's next

Operator/MCP follow-up: [`planning/operator-followups/group-human-task-executors.md`](../../planning/operator-followups/group-human-task-executors.md).
Rides schema v5 from the tie-break PR (no further bump).

```decision-log
| 2026-11-02 | Humans execute task-force tasks after the agents' waves, one task per pause | A person inside a wave future would hold every agent's wave budget hostage; one pause per task keeps a single pendingHumanInput and one content-to-task mapping | Humans inside waves; batching several tasks into one pause |
| 2026-11-02 | targetEachPeer phases and nested groups containing humans stay refused | The resume bookmark has no (speaker, target) coordinate, and nested pauses would need parent-follows-child pause propagation across two documents | Extending ResumePoint for peer targets in this PR |
| 2026-11-02 | VERIFY rework skips HUMAN-assigned tasks | A rework would pause from inside VERIFY, and resuming VERIFY replays the whole verification pass | Reworking human tasks with a VERIFY-phase pause |
```
