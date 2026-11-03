## ✨ feat(groups): `tiePolicy: HUMAN_DECIDES` — a named person breaks a tied vote (2026-11-02)

**Repo:** EDDI (`feat/group-human-tiebreak`)

### What changed and why

`HUMAN_DECIDES` was a real enum value that every save refused: a tie that waits on a person needed resume
machinery a finished VOTE phase did not have. It now pauses the discussion until the decider chooses, riding
I6's human-input path rather than the approval path.

```json
"voteConfig": {
  "method": "MAJORITY", "optionsSource": "EXPLICIT", "options": ["Double down on SEO", "Launch the paid pilot"],
  "tiePolicy": "HUMAN_DECIDES",
  "humanTieBreak": { "decider": "founder@acme.com", "timeout": "PT24H", "onTimeout": "MODERATOR_DECIDES" }
}
```

- **Config** — [`HumanTieBreakConfig`](../../src/main/java/ai/labs/eddi/configs/groups/model/HumanTieBreakConfig.java)
  as an eighth `VoteConfig` component (the seven-argument constructor stays). Save time
  ([`GroupGapsValidation`](../../src/main/java/ai/labs/eddi/configs/groups/validation/GroupGapsValidation.java),
  called from `AgentGroupStore.validateVotePhases` in place of the old throw) needs a resolvable decider (named,
  or the single HUMAN member), a positive ISO-8601 timeout, and a moderator for `MODERATOR_DECIDES`.
- **Engine** — `PhaseExecutionEngine.recordVoteDecision` records the NONE tally as before and returns a
  `PendingTieBreak` ([`HumanTieBreakEngine`](../../src/main/java/ai/labs/eddi/engine/internal/groups/HumanTieBreakEngine.java):
  decider, prompt from option texts and totals only, choice matching, decision building).
  `GroupConversationService` pauses on it after the persist and `onPhaseComplete`, after applying a deferred
  `INSERT_VOTE` and dropping a deferred escalation with a note.
  [`GroupTieBreakPauses`](../../src/main/java/ai/labs/eddi/engine/internal/groups/GroupTieBreakPauses.java) commits
  the pause (`speakerIdx = -1`, `RESUME_KIND_TIE_BREAK`, no turn charged), resolves a choice (validated before
  any mutation, CAS out of the pause, rollback on executor refusal) into a `vote+human-tiebreak` VOTE record with
  the losing ballots as dissents, and resumes at the next phase — or pauses for the vote phase's own approval
  gate. Timeouts: `NO_DECISION`, `MODERATOR_DECIDES` (turn- and cost-gated, one turn) or `ABORT` (the plain ABORT
  policy, so the timeout handler needs no change). `GroupHitlCoordinator` dispatches to it from
  `submitHumanInput` and `skipHumanTurnOnTimeout`.
- **Persisted shape** — `PendingHumanInput` gains a `detail`
  ([`HumanRequestDetail`](../../src/main/java/ai/labs/eddi/configs/groups/model/HumanRequestDetail.java): kind, options,
  taskId, transcriptFrom, timeoutResolution); the nine-argument constructor stays. **Schema v5**
  (`GroupConversation.CURRENT_SCHEMA_VERSION`), an identity hop, so an older pod refuses a tie-break pause instead
  of re-running the vote.
- **Surfaces** — REST `HumanInputRequest` gains an optional `choice` (and `content` loses `@NotBlank`; the service
  keeps requiring it for every other kind); MCP `submit_group_human_input` gains an optional `choice`; the REST and
  MCP status summaries add `pendingKind`/`pendingOptions`/`pendingTaskId`
  ([`PendingHumanInputView`](../../src/main/java/ai/labs/eddi/engine/internal/groups/PendingHumanInputView.java)), the
  inbox `PendingApprovalSummary` a `pendingKind`; `HumanInputRequestedEvent` carries the `detail`; Slack posts a
  tie-break notice (still notification-only).
- **Audit and metrics** — one `hitl.approval` entry per resolution: `verdict TIE_BROKEN`,
  `approvalCategory group.vote_tiebreak`, `reasonCode`, options, choice — and no `decisionOutcome`, because a
  tie-break is a human turn. Counter `eddi.group.vote.tiebreaks{policy, resolution}`.
- **Manager** — the phase editor enables HUMAN_DECIDES with decider/timeout/on-timeout fields; the human-turn
  banner's tie-break variant asks for one option plus an optional rationale; the inbox labels tie-breaks. New i18n
  namespace `groupTieBreak` (11 locales).
- **Docs** — new page [Group Human Decisions: Tie-Breaks](../group-human-decisions.md); links from
  [Group Conversations](../group-conversations.md) and [HITL](../hitl.md); the AGENTS.md roadmap row no longer
  lists HUMAN_DECIDES as pending.

### Compatibility

Every other tie policy behaves as before. A document without `detail` is a speaking turn; a pending record's
`onTimeout` is still `SKIP_TURN`/`ABORT`. The schema bump to v5 collides with any other branch that bumps it —
whichever merges second renumbers.

### What's next

Humans as task-force executors (`feat/group-human-task-executors`) reuse `HumanRequestDetail` (`kind TASK`).
Operator/MCP follow-up: [`planning/operator-followups/group-human-tiebreak.md`](../../planning/operator-followups/group-human-tiebreak.md).

```decision-log
| 2026-11-02 | A HUMAN_DECIDES tie-break pauses through the I6 AWAITING_HUMAN_INPUT path, not the approval path | Approval endpoints are binary and carry no content, and any eddi-approver could then set the outcome of a vote; the I6 path already has the decider-or-admin guard, timeouts, crash recovery, CAS and the inbox | A PHASE approval with the choice in the note; letting approvers break ties |
| 2026-11-02 | GroupConversation schema v5 for PendingHumanInput.detail and RESUME_KIND_TIE_BREAK | Both are resume-consumed: an older pod resolving a tie-break would re-run the vote's last repeat | Riding v4 because the fields are additive |
| 2026-11-02 | A tie-break audit entry carries no decisionOutcome | It is a human turn, not an approval; the approval-analytics contract skips null outcomes, and new outcome values would widen that contract | DECIDED / TIMED_OUT outcome values |
```
