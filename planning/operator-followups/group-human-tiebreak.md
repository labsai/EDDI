# Operator / MCP follow-up — `feat/group-human-tiebreak` (F8b)

Deferred to the combined F9 integration PR (no edits to `ui/manager/src/lib/operator/**`, no new MCP tools in
feature PRs).

## Platform Operator — what the system prompt should learn

- `voteConfig.tiePolicy: "HUMAN_DECIDES"` with `voteConfig.humanTieBreak { decider, timeout, onTimeout }`:
  - `decider` defaults to the group's single HUMAN member; with none or several the save is a 400 until one is
    named. The decider need not be a member.
  - `timeout` (ISO-8601, positive) falls back to `humanMemberConfig.turnTimeout`; neither = wait indefinitely.
  - `onTimeout`: `NO_DECISION` (default) | `MODERATOR_DECIDES` (needs `moderatorAgentId`) | `ABORT`.
- A tie pauses the discussion in `AWAITING_HUMAN_INPUT`, `pauseType HUMAN_TURN`, with `pendingKind: TIE_BREAK`
  and `pendingOptions` in `get_group_approval_status` / the REST approval-status summary, and a Tie-break badge in
  the inbox.
- Only the decider or an admin may answer, on the human-input endpoint / `submit_group_human_input`, with
  `choice` = exactly one option (`content` = optional rationale). The decision method is `vote+human-tiebreak`.
- A tie-break costs no turn; a `MODERATOR_DECIDES` timeout fallback costs one.
- **The operator must not be granted `human-input` (or `submit_group_human_input`).** Answering a tie as the
  operator would be impersonating the decider. The prompt should say so explicitly and send the admin to the
  decider instead.
- Schema v5: a pod older than this release refuses to resume such a document (by design).

Changing the prompt means an `operator-revision.json` bump in the F9 PR. No allow-list change: no new endpoint.

## MCP tools

None new. `submit_group_human_input` gained an optional `choice` argument in this PR (allowed: optional
arguments on an existing tool). `get_group_approval_status` now returns `pendingKind`, `pendingOptions` and
`pendingTaskId`.
