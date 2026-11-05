# Group Human Decisions: Tie-Breaks

A group can put a person where it would otherwise guess. This page covers **`tiePolicy: HUMAN_DECIDES`**: a
tied (or quorum-failed) vote pauses the discussion until a named person picks the winner. It builds on
[humans as group members](group-conversations.md#humans-as-group-members-i6) and on the
[VOTE phase](group-conversations.md) — read those first.

## Configuration

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

## What happens

1. The VOTE phase tallies as usual. On a tie or a quorum failure the discussion records the `NONE` decision
   with its tally, persists it and fires `decision_reached` for it, so the tally is visible while it waits.
2. The discussion pauses in `AWAITING_HUMAN_INPUT`, as a **non-speaker** human turn (`pauseType: HUMAN_TURN`,
   the same machinery as a member's turn and a facilitator escalation). The pending input carries
   `detail.kind: "TIE_BREAK"` and the options; the status and MCP summaries show `pendingKind: "TIE_BREAK"`
   and `pendingOptions`, the inbox shows a **Tie-break** badge, the `human_input_requested` event carries the
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

### Who may answer

Two checks, the access rule of every human turn. The caller needs a role the endpoint admits (`eddi-user`,
`eddi-editor`, `eddi-approver` or `eddi-admin` — an `eddi-viewer` cannot answer, even as the decider), and
must be the decider's own principal unless they are an `eddi-admin`. So `eddi-approver` lets a decider answer
but never lets anyone answer *for* the decider: setting the outcome of someone else's vote is not reviewing it.
The principal check needs authentication — with it disabled, no caller is compared with the decider, so any
client that passes the request guards can answer. The second of two racing answers (or an answer racing the
timeout) gets `409`.

### Timeouts

| `onTimeout` | Result |
| --- | --- |
| `NO_DECISION` | A `SKIPPED` entry and a `NONE` record, "No decision from <decider> within <window>"; the discussion moves on |
| `MODERATOR_DECIDES` | One moderator turn chooses among the options, gated on `maxTurns` and the cost ceiling exactly as the policy is when a vote ties; with the budget spent, or an unreadable reply, the `NONE` record stands |
| `ABORT` | The discussion is cancelled, as for a human turn's `ABORT` |

Timeouts are scheduled, re-armed after a restart and bound to the pause they were armed for, like every
other human turn.

## Spend and turns

A human choice makes no model call and is **not charged as a turn**. The `MODERATOR_DECIDES` timeout
fallback is one turn, like the policy it borrows.

## Audit and metrics

With the audit ledger enabled, each resolution writes one `hitl.approval` audit entry with `verdict: "TIE_BROKEN"`,
`approvalCategory: "group.vote_tiebreak"`, `reasonCode` (`HUMAN_CHOICE`, `TIMEOUT_NO_DECISION` or
`TIMEOUT_MODERATOR`), the options and the choice. It is a human turn, not an approval, so it carries **no
`decisionOutcome`** — approval analytics skip it. When a meter registry is present (the default with
metrics enabled), counter `eddi.group.vote.tiebreaks{policy, resolution}` counts resolutions (`human`, `timeout_none`, `timeout_moderator`).

## Interactions

- A facilitator escalation deferred to the boundary where the vote tied is recorded as a `FACILITATION`
  note and dropped: the decider is about to be asked anyway, and a boundary pauses once. A facilitator's
  `INSERT_VOTE` is still applied.
- The group conversation schema is **v5** from this release, and **every** new discussion is stamped v5,
  whether or not it uses `HUMAN_DECIDES`. A pod that predates it refuses to resume any v5 document — a
  tie-break, but also an ordinary approval or human turn — rather than re-running a vote. During a rolling
  upgrade, finish the rollout before relying on resumes.
- Slack stays notification-only for tie-breaks: a Slack user is not the decider's EDDI principal, so the
  choice is made in EDDI.
