## 🐛 fix(groups): turn and discussion outcomes, cancel that cancels, fail-safe verification (2026-11-18)

**Repo:** EDDI (`fix/group-outcomes-and-cancel`)

Findings K1, K2, K3, K4 (verifier part), K7, K8, K9 of the November 2026 review, live
GR1–GR4.

### What changed and why

1. **A member turn has an outcome** (`ANSWERED`, `SKIPPED`, `FAILED`, `TIMED_OUT`) on its
   transcript entry (`TranscriptEntry.outcome`). A member whose conversation ends in
   `ERROR` without output used to become the text "[Agent failed to produce output —
   conversation entered ERROR state]", signed and stored as a real OPINION/SYNTHESIS,
   quoted to peers and taken as the synthesized answer — a provider outage completed as
   a success with that sentence as its answer. It is now a `FAILED` turn with no
   content that counts against `onAgentFailure` (RETRY retries it, ABORT aborts). The
   record forces content to `null` for every outcome other than `ANSWERED`, so no reader
   (peer prompt, vote, stance, judge, synthesis) can quote a failed turn. The placeholder
   is gone from all four places (`MemberTurnExecutor` twice, `GroupLifecycleOps`
   follow-up — now a 502 — and `ConverseWithAgentTool`, which now reports a failure).
   `errorReason` is a fixed text; exception and provider text goes to the log only (K9).
   `speaker_complete` carries the outcome on the sequential and peer-targeted paths too.

2. **A discussion that ends without an answer says so.** New fields
   `GroupConversation.outcome` (`ANSWERED` | `NO_ANSWER` | `FAILED`) and `outcomeReason`
   (`NO_MEMBER_ANSWERED`, `SYNTHESIS_FAILED`, `SYNTHESIS_NOT_RUN`, `TURN_LIMIT_REACHED`,
   `COST_CEILING_REACHED`, `DISCUSSION_ERROR`), set when a round ends
   (`DiscussionOutcomes`), carried by REST, the MCP tools and the `group_complete` event.
   A round in which no member answered because member turns failed or timed out ends in
   the existing `FAILED` state; every other round without an answer stays `COMPLETED`
   with `outcome: NO_ANSWER`. The Slack listener
   posts a notice instead of ending in silence. Counter
   `eddi_group_discussion_unanswered_total{outcome,reason}`.
3. **The turn cap keeps the synthesis turn** (`TurnBudget`): one turn per remaining
   `SYNTHESIS` phase is reserved, so 10 members × 5 rounds under the default 50 turns
   still ends with a synthesis (it used to drop it silently). A cut leaves one `System`
   `SKIPPED` note per round ("Turn limit (protocol.maxTurns) reached …").
4. **Cancel that cancels.** The control token is read before every speaker
   (sequential, peer-targeted, parallel batch) and every repeat, not only at phase
   boundaries. `CANCEL_IMMEDIATE` (used by delete) releases the running turn and cancels
   its member conversation's turn. A member conversation ended under a deleted or ended
   discussion is no longer replaced by a fresh one. Nested groups hear the parent's cancel
   (`DiscussionControlToken.onCancel`) and inherit its remaining turns
   (`GroupConversation.inheritedMaxTurns`; the child's turns are charged back). The cancel
   endpoint answers `202` while the running discussion finishes its current turn, `200`
   once it is cancelled; the MCP tool answers `CANCELLING` / `CANCELLED`.
5. **Verification and retries fail safe.** A VERIFY pass whose verifier did not answer
   fails its tasks with "Not verified: the verifier did not answer" (counter
   `eddi_group_verify_unanswered_total`) instead of auto-verifying them. A timeout `RETRY`
   first cancels the timed-out turn and waits up to 5 s for it; if it does not stop, the
   turn is recorded `TIMED_OUT` without a retry instead of queueing a second request
   behind the first.
6. **Group validation.** A group with no members is refused on save, and starting a
   discussion on one stored by an older version is refused before anything is created:
   REST `422`, an SSE `group_error`, an MCP error. A group whose members all are unknown
   or undeployed agents still runs (each turn `SKIPPED`) but ends `COMPLETED` with
   `outcome: NO_ANSWER`, `outcomeReason: NO_MEMBER_ANSWERED` instead of a plain success.

### Design decisions

- An outcome field next to the state instead of a new terminal state: the Manager,
  Workforce, Slack, MCP and stored discussions switch on the state names; only "no member
  answered" uses the existing `FAILED`.
- A failed member turn keeps the `SKIPPED` entry type (the parallel exception path keeps
  `ERROR`); the outcome carries the meaning, so existing readers that filter on type or
  content keep excluding it.
- The synthesis turn is reserved (work phases stop earlier) rather than exempted from
  `maxTurns`, so `maxTurns` stays a hard cap.
- The unparseable-verdict default (auto-verify) is left to open PR #1395, which adds
  `taskListConfig.onUnparseableVerdict` and fences VERIFY input; this branch only fails
  the case where the verifier gave no answer at all.

### Behaviour changes for upgraders

- Entries stored before this change carry no `outcome`; they read as `ANSWERED`
  (`SKIPPED`/`ERROR` entries as `SKIPPED`/`FAILED`). Old placeholder entries keep their text.
- A follow-up whose member fails answers 502 instead of recording the placeholder.
- A first round in which every member turn failed or timed out is `FAILED`, not
  `COMPLETED`; a later round that fails stays `COMPLETED` with `outcome: FAILED`
  (`outcome` always describes the latest round).
- A decision record of type `NONE` is not an answer.
- Member turns that did not answer are no longer in other members' `groupTranscript` context.
- `maxTurns`-bound discussions run one opinion turn fewer per remaining synthesis phase.
- `POST …/cancel` on a running discussion answers `202` (was `200`); `200` now means "no
  longer running", with the state it ended in.
- Saving a group without members, or starting a discussion on one, is refused.
- A TASK_FORCE whose verifier is not deployed or fails no longer passes every task.

### Review follow-ups (same day)

- **Legacy placeholder (M1):** a stored entry without `outcome` whose content is the old
  failure placeholder reads as `FAILED` without content; a stored `synthesizedAnswer`
  holding it reads as no answer. Recognised on read only (one private constant).
- **NONE decisions (M2):** a decision record of type `NONE` (dissents only, no quorum, a
  tie, an unreadable verdict) no longer makes a round `ANSWERED`.
- **Failed later rounds (M3):** `outcome` describes the latest round. The state becomes
  `FAILED` only when no round ever answered (new persisted counter `answeredRounds`; a
  legacy continued document counts as answered); a failed continuation stays
  `COMPLETED`, continuable, its agents kept.
- **Turn budget (S1, S2):** a parallel batch is counted before dispatch, nested GROUP
  members claim a share of what remains (`claimLegTurns`), and a child is charged in a
  `finally` (its whole claim when it threw).
- **Cancel everywhere (S4):** every member or engine turn checks the token
  (`MemberTurnExecutor.executeAgentTurn`) — judge, facilitator, dissent, vote tie-break,
  task-force plan/verify/bids — and turns without their own cancellation are released by
  `CANCEL_IMMEDIATE`; released parallel speakers get a `SKIPPED` "Cancelled" entry and
  `speaker_complete`.
- **Cancel answer (S5):** `200` means "no longer running — read `state`" (CANCELLED, or
  COMPLETED/FAILED when it finished first); the MCP tool returns that state.
- **Verifier (S6):** a SKIPPED verifier fails its tasks once and stops the rework loop.
- **One vocabulary (S7):** reasons in one enum `DiscussionOutcomes.Reason`, new
  `REJECTED_BY_HUMAN`, `APPROVAL_TIMED_OUT`, `NO_PROGRESS`; a reject and the no-progress
  failure set them and `group_complete` carries them; turn-cap skips report
  `TURN_LIMIT_REACHED`; skipped members with a failed synthesis report `SYNTHESIS_FAILED`.
- **Nested children (S8):** a child that was cancelled is a `SKIPPED` turn, one that failed
  or ended without an answer a `FAILED` turn; its partial entries are never the answer.
- **Peer context:** member turns that did not answer are left out of the `groupTranscript`
  context handed to other members.
- **Timeout retry (S3):** documented as cooperative — a retry happens only when the
  timed-out attempt stops within 5 s.

### Left open

- Unparseable verdicts still auto-verify by default (PR #1395 adds the option; its
  default should be `FAIL`). A member turn stopped by `CANCEL_IMMEDIATE` is not harvested
  for its cost or created agents. The timeout retry uses `cancelConversation` (a sibling
  branch adds an in-flight-only cancel); a timed-out turn that finishes late leaves its
  reply in the member's private history unmarked.

```decision-log
| 2026-11-18 | Discussion outcome is a field (`outcome`/`outcomeReason`) beside the state; only "no member answered" maps to FAILED | A provider outage and a turn-cap cut both ended COMPLETED with no way to tell | A new terminal state (every consumer switches on the state names) |
```

**Files:** [`GroupConversation.java`](../../src/main/java/ai/labs/eddi/configs/groups/model/GroupConversation.java),
[`MemberTurnExecutor.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/MemberTurnExecutor.java),
[`PhaseExecutionEngine.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/PhaseExecutionEngine.java),
[`GroupLifecycleOps.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/GroupLifecycleOps.java),
[`GroupConversationEventSink.java`](../../src/main/java/ai/labs/eddi/engine/lifecycle/GroupConversationEventSink.java),
[`ConverseWithAgentTool.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/ConverseWithAgentTool.java),
[`DiscussionOutcomes.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/DiscussionOutcomes.java),
[`TurnBudget.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/TurnBudget.java),
[`DiscussionCancellation.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/DiscussionCancellation.java),
[`GroupMemberPreflight.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/GroupMemberPreflight.java),
[`DiscussionControlToken.java`](../../src/main/java/ai/labs/eddi/engine/lifecycle/model/DiscussionControlToken.java),
[`GroupConversationService.java`](../../src/main/java/ai/labs/eddi/engine/internal/GroupConversationService.java),
[`TaskForceEngine.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/TaskForceEngine.java),
[`AgentGroupStore.java`](../../src/main/java/ai/labs/eddi/configs/groups/mongo/AgentGroupStore.java),
[`RestGroupConversation.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestGroupConversation.java),
[`McpHitlTools.java`](../../src/main/java/ai/labs/eddi/engine/mcp/McpHitlTools.java),
[`SlackGroupDiscussionListener.java`](../../src/main/java/ai/labs/eddi/integrations/slack/SlackGroupDiscussionListener.java);
docs: [`group-conversations.md`](../group-conversations.md), [`metrics.md`](../metrics.md).
