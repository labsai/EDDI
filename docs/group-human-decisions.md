# Group Human Decisions: Tie-Breaks and Tasks

A group can put a person where it would otherwise guess. This page covers two places:

- **`tiePolicy: HUMAN_DECIDES`** — a tied (or quorum-failed) vote pauses the discussion until a named person
  picks the winner ([Tie-breaks](#tie-breaks));
- **humans as task-force executors** — a HUMAN member works the PLAN/EXECUTE/VERIFY tasks assigned to them
  ([below](#humans-as-task-force-executors)).

Both build on [humans as group members](group-conversations.md#humans-as-group-members-i6) — read that first.

## Tie-breaks

### Configuration

```json
{
  "name": "Board vote",
  "type": "VOTE",
  "turnOrder": "PARALLEL",
  "contextScope": "NONE",
  "voteConfig": {
    "method": "MAJORITY",
    "optionsSource": "EXPLICIT",
    "options": ["Double down on SEO", "Launch the paid pilot"],
    "tiePolicy": "HUMAN_DECIDES",
    "humanTieBreak": { "decider": "founder@acme.com", "timeout": "PT24H", "onTimeout": "MODERATOR_DECIDES" }
  }
}
```

| Field | Default | Meaning |
| --- | --- | --- |
| `decider` | the group's single HUMAN member | The principal id that decides. It need not be a member — a board can defer to someone who does not sit on it, like a facilitator's `escalateTo`. A group with no HUMAN member, or several, must name one: saving without a resolvable decider is a `400` |
| `timeout` | `humanMemberConfig.turnTimeout`, else none | ISO-8601 duration the decider has. Must be positive. With neither set, the pause waits indefinitely |
| `onTimeout` | `NO_DECISION` | `NO_DECISION` keeps the honest `NONE` record; `MODERATOR_DECIDES` lets the moderator choose (a `400` when the group has no `moderatorAgentId`); `ABORT` cancels the discussion |

The Manager's phase editor shows these fields when **On a tie** is set to **A human decides**.

### What happens

1. The VOTE phase tallies as usual. On a tie or a quorum failure the discussion records the `NONE` decision
   with its tally, persists it and fires `decision_reached` for it, so the tally is visible while it waits.
2. The discussion pauses in `AWAITING_HUMAN_INPUT`, as a **non-speaker** human turn (`pauseType: HUMAN_TURN`,
   the same machinery as a member's turn and a facilitator escalation). The pending input carries
   `detail.kind: "TIE_BREAK"` and the options; the status and MCP summaries show `pendingKind: "TIE_BREAK"`
   and, to a caller who may read the pending content (the owner, the decider or an admin), `pendingOptions` —
   an approver who is neither gets the summary without it, so clients must handle its absence; the inbox shows a **Tie-break** badge, the `human_input_requested` event carries the
   `detail`, and Slack posts a notice naming the decider and the options. The decider's prompt is built from
   the option texts and the weighted totals only, never from member statements.
3. The decider answers on the human-input endpoint with a **choice**:

   ```http
   POST /groups/{groupId}/conversations/{gcId}/human-input
   {"memberId": "founder@acme.com", "choice": "Launch the paid pilot", "content": "We need revenue this quarter"}
   ```

   `choice` must name exactly one of the options (an exact match, or the same whole-word rule a moderator's
   tiebreak reply gets); anything else is a `400` that lists them, and nothing changes. `content` is an
   optional rationale. Over MCP, `submit_group_human_input` takes the same `choice` argument — optional in the tool's schema,
   because other human turns have none, but required for a tie-break.
4. The decision becomes a `VOTE` record — winner the chosen option, outcome `"… chosen by <decider> (tie
   break)"`, method **`vote+human-tiebreak`**, the original tally, and the losing ballots' statements as
   dissents (re-read from the transcript, not stored twice). A peer-visible `HUMAN_INPUT` entry records who
   broke the tie and why.
5. The discussion moves on from the phase **after** the vote. A VOTE phase with `requiresApproval` pauses for
   that approval next — the decision first, then the gate — and approving resumes after the vote as usual. A
   VOTE that is the last phase completes the discussion.

#### Who may answer

Two checks, the access rule of every human turn. The caller needs a role the endpoint admits (`eddi-user`,
`eddi-editor`, `eddi-approver` or `eddi-admin` — an `eddi-viewer` cannot answer, even as the decider), and
must be the decider's own principal unless they are an `eddi-admin`. So `eddi-approver` lets a decider answer
but never lets anyone answer *for* the decider: setting the outcome of someone else's vote is not reviewing it.
The principal check needs authentication — with it disabled, no caller is compared with the decider, so any
client that passes the request guards can answer. The second of two racing answers (or an answer racing the
timeout) gets `409`.

#### Timeouts

| `onTimeout` | Result |
| --- | --- |
| `NO_DECISION` | A `SKIPPED` entry and a `NONE` record, "No decision from <decider> within <window>"; the discussion moves on |
| `MODERATOR_DECIDES` | One moderator turn chooses among the options, gated on `maxTurns` and the cost ceiling exactly as the policy is when a vote ties; with the budget spent, or an unreadable reply, the `NONE` record stands |
| `ABORT` | The discussion is cancelled, as for a human turn's `ABORT` |

Timeouts are scheduled, re-armed after a restart and bound to the pause they were armed for, like every
other human turn.

### Spend and turns

A human choice makes no model call and is **not charged as a turn**. The `MODERATOR_DECIDES` timeout
fallback is one turn, like the policy it borrows.

### Audit and metrics

With the audit ledger enabled, each resolution writes one `hitl.approval` audit entry with `verdict: "TIE_BROKEN"`,
`approvalCategory: "group.vote_tiebreak"`, `reasonCode` (`HUMAN_CHOICE`, `TIMEOUT_NO_DECISION` or
`TIMEOUT_MODERATOR`) and the options; the choice is recorded only for a human submission (`HUMAN_CHOICE`) — a
timeout entry carries none, and `TIMEOUT_MODERATOR` does not record the moderator's eventual selection. It is a human turn, not an approval, so it carries **no
`decisionOutcome`** — approval analytics skip it. When a meter registry is present (the default with
metrics enabled), counter `eddi.group.vote.tiebreaks{policy, resolution}` counts resolutions (`human`, `timeout_none`, `timeout_moderator`).

### Interactions

- A facilitator escalation deferred to the boundary where the vote tied is recorded as a `FACILITATION`
  note and dropped: the decider is about to be asked anyway, and a boundary pauses once. A facilitator's
  `CALL_VOTE` move is still applied.
- The group conversation schema is **v5** from this release, and **every** new discussion is stamped v5,
  whether or not it uses `HUMAN_DECIDES`. A pod that predates it refuses to resume any v5 document — a
  tie-break, but also an ordinary approval or human turn — rather than re-running a vote. During a rolling
  upgrade, finish the rollout before relying on resumes.
- Slack stays notification-only for tie-breaks: a Slack user is not the decider's EDDI principal, so the
  choice is made in EDDI.

## Humans as task-force executors

A HUMAN member can sit in a `TASK_FORCE` (or any group with PLAN/EXECUTE/VERIFY phases) and **execute tasks**:
a legal sign-off, a customer call, a judgement nobody wants an agent to make.

```json
{
  "style": "TASK_FORCE",
  "moderatorAgentId": "lead",
  "members": [
    { "agentId": "lead", "displayName": "Lead", "speakingOrder": 0 },
    { "agentId": "writer", "displayName": "Writer", "speakingOrder": 1 },
    { "agentId": "hannah@acme.com", "displayName": "Hannah", "speakingOrder": 2, "role": "LEGAL", "memberType": "HUMAN" }
  ],
  "humanMemberConfig": { "turnTimeout": "PT4H", "onTimeout": "SKIP_TURN" },
  "tasks": [
    { "subject": "Draft the announcement", "description": "…", "assignToRole": "ALL" },
    { "subject": "Legal sign-off", "description": "…", "assignToRole": "ROLE:LEGAL", "dependsOn": ["Draft the announcement"] }
  ]
}
```

### Assignment is explicit

A task reaches a person only when it names them: `assignToRole: "ROLE:<role>"` or their principal id, a task an
agent files for them, or the LLM planner naming them (the planner is told which members are human and that their
work is slow). **Round-robin (`"ALL"`) never picks a HUMAN member, and neither do bids** — humans do not bid, and a
BID task nobody bid on falls back to ROLE assignment, which may land on a person.

### Agents first, then one task per pause

The EXECUTE wave loop leaves HUMAN-assigned tasks out of its waves, so a person never holds up a wave and the wave
budget is unchanged. Once no agent task is left to run but a human task is executable, the discussion pauses in
`AWAITING_HUMAN_INPUT` for the highest-priority one (`priority` 0 first): the prompt is the same task input an agent
would get, the pending input carries `detail.kind: "TASK"` and the task id (`pendingKind: TASK`, `pendingTaskId` in the
summaries), the inbox shows a **Task** badge and Slack says a task is ready. The member answers on the usual
human-input endpoint with `content` — their result.

- The result **completes** the task (recorded as a `TASK_RESULT` entry) — or, when the EXECUTE phase gates tasks for
  approval (`hitlConfig.granularity: TASK` and `requiresApproval`), submits it for approval exactly as an agent's
  result would be. The resumed wave loop then runs whatever depended on it, and pauses for the next human task.
- A human task **counts as a turn** (the human-turn rule) and costs $0.
- A `SKIP_TURN` timeout **fails** the task ("No response from <name> within <window>"); `ABORT` cancels the
  discussion. Reassigning an unanswered task to an agent is not supported.

### Verification

Agents verify human work like any other. `maxReworks` **skips** HUMAN-assigned tasks: a failed human task stays
`FAILED` with the verifier's feedback and a *"Not reworked: … is assigned to a human"* note, because reworking it
would pause from inside VERIFY, and resuming a VERIFY phase replays the whole verification pass. For human review of
*agent* work, use TASK-granularity approval ([HITL](hitl.md)).

### Still refused at save time

- **A HUMAN planner or verifier.** In a group with PLAN or VERIFY phases, the moderator — or, without one, the first
  member by speaking order, who stands in — must be an agent: planning and verification parse a structured reply
  inside one turn that has no resume point, and prose from a person would silently auto-verify every task.
- **`targetEachPeer` phases with humans.** A person would owe one critique per peer *and* be a target; the resume
  bookmark has no (speaker, target) coordinate, and adding one changes the resume path of every pause kind.
- **Nested groups containing humans.** A sub-group that pauses is cancelled and its member turn recorded as
  `SKIPPED`; supporting it needs a parent pause that follows the child's, across two documents.

A standing team's cadence reclaims a run whose claim outlives `eddi.groups.cadence.claim-ttl` (default `PT24H`), so
give human members a `turnTimeout` well inside it.
