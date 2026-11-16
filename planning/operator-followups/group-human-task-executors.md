# Operator / MCP follow-up — `feat/group-human-task-executors` (F8c)

Deferred to the combined F9 integration PR (no edits to `ui/manager/src/lib/operator/**`, no new MCP tools in
feature PRs). Stacked on `feat/group-human-tiebreak`; read that follow-up too.

## Platform Operator — what the system prompt should learn

- HUMAN members may now be members of task-force groups (PLAN/EXECUTE/VERIFY phases) as **task executors**:
  - only by explicit assignment — `assignToRole: "ROLE:<role>"`, the human's principal id, an agent-filed task, or
    the planner naming them. Round-robin (`"ALL"`) and bids never pick a human;
  - one `AWAITING_HUMAN_INPUT` pause per task, after the agents' waves, with `pendingKind: TASK` and
    `pendingTaskId` in `get_group_approval_status`;
  - the member answers with `content` (their result) on the human-input endpoint / `submit_group_human_input`;
  - a `SKIP_TURN` timeout fails the task; `ABORT` cancels the discussion;
  - under TASK-granularity HITL with `requiresApproval`, the human's result is submitted for approval like an
    agent's;
  - `maxReworks` skips human tasks (they stay FAILED with a note).
- Still refused at save time: a HUMAN planner/verifier (the moderator, or the first member by speaking order without
  one) in a group with PLAN or VERIFY phases; `targetEachPeer` phases with humans; nested groups containing humans.
- Advice for standing teams: keep `humanMemberConfig.turnTimeout` well inside `eddi.groups.cadence.claim-ttl`
  (default PT24H), or the cadence reclaims the run first.
- As for tie-breaks, the operator must **not** answer a human task — that would be impersonating the member.

Changing the prompt means an `operator-revision.json` bump in the F9 PR. No allow-list change.

## MCP tools

None new. `submit_group_human_input` already carries `content`; `get_group_approval_status` reports
`pendingKind`/`pendingTaskId` since the tie-break PR.
