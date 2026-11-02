## 🐛 fix(groups): review findings — HITL scope, follow-up budget, task-force results, negotiation, JSON extraction (2026-10-31)

**Repo:** EDDI (`fix/review-groups`)

Fixes from the external code review and the CodeRabbit pass against `01c3250`, group-conversation area.

### What changed and why

**HITL authority and read scope**

- **Approvers can no longer cancel another user's running discussion.** The group cancel on REST and on the MCP
  `cancel_group_discussion` tool now uses `HitlAccessGuard.mayCancelExecuting`, like the 1:1 surface: an
  approver-only caller gets a paused-only cancel (`cancelPausedDiscussion`, one compare-and-set on
  `AWAITING_APPROVAL` / `AWAITING_HUMAN_INPUT`) and `403` / `FORBIDDEN` on a running discussion. The Javadoc that
  claimed both surfaces already did this is now true.
- **Responses no longer hand the transcript to callers who may not read it.** The JSON approve, the human-input
  submission and the approver cancel return the conversation only to its owner or an admin; an approver or a
  pending HUMAN member gets `{"state": …}`. MCP `approve_group_phase` and `submit_group_human_input` match.
- **A human-turn resume no longer runs as the submitter.** `resolveHumanTurn` bound the submitting member's
  identity to the rest of the owner's discussion, so later `${caller:token}` calls went out with their token. It now
  uses `resumeCallerFor`, the rule the approval resume already followed.
- **Group HITL timeouts are bound to their pause.** Approval `ABORT`, human-turn `ABORT` and `SKIP_TURN` pass the
  schedule's `pauseId`; a late fire no longer skips a newer human turn or cancels a discussion that has resumed.
  Stale `skipHumanTurnOnTimeout` Javadoc ("never throws") corrected.

**Discussion engine**

- **Follow-ups respect the cost ceiling and are accounted.** `followUpWithMember` refuses once the effective
  `maxCostPerDiscussion` is spent (`409`), harvests the member's cost into the ledger, and propagates
  dynamic-agent tracking so sub-agents created in a follow-up are cleaned up with the discussion.
- **A skipped or errored task-force member fails its task.** It used to complete the task with a `null` result,
  unblocking dependents that then ran without its output. `SharedTaskList.completeTask` / `submitForApproval` now
  refuse a `null` result.
- **Negotiation cannot record a stale agreement.** Accepting a proposal withdraws the signer's signature from every
  other open proposal. An agreement keeps the dissents a dissent round recorded, and an arbitration verdict now
  replaces a dissent-only `NONE` record instead of being dropped.
- **One balanced JSON extractor for every group engine** (`EmbeddedJson`): facilitator, retro, vote, negotiation,
  bid, convergence and debate parsers, and the VERIFY phase. The greedy first-`{`-to-last-`}` slice dropped a
  ballot followed by a brace in prose; the first-`[`-to-last-`]` slice made VERIFY fail open on any bracketed
  aside. Each engine accepts only an object carrying a key it reads (`vote`/`votes`/`statement`, `bids`, `move`,
  `lessons`, `accept`/`proposal`/`concessions`, `passed`, `agreementScore`, `winner`), so an unrelated object in
  the prose (an example, a quoted config, `{}`) is neither taken for the reply nor a rival to it. A reply carrying
  two *well-shaped* values is ambiguous and rejected — unchanged for the greedy engines (two objects never parsed
  as one), but a behaviour change for bids and bargain moves, which used to take the first. The documented
  fail-open on a truly unparseable verdict is unchanged.
- **`groupTranscript` is scoped** by the phase's `contextScope`, like the rendered prompt, and a member
  conversation's startup step now receives the group's dynamic-agent policy instead of the permissive standalone
  default.
- **Stance extraction is linear.** `leadSentence` rescanned the prefix at every terminator.

**Dead code:** `GroupConversationService.DEFAULT_ENV`, `PhaseOutcome.endDiscussion` / `END_DISCUSSION` and the
unreachable `endDiscussionEarly` handling, and the `findMember` / `findMemberIncludingDynamic` reflective
delegators (covered directly by `TaskForceEngineTest`).

### Not done

- Retention revocation (`unretainAgent`) still does not propagate to the group registry, and the
  discussion-wide creation cap is still checked against a per-member copy. Both need a change in
  `modules/llm` (`DynamicAgentToolsProvider` / `CreateSubAgentTool`) to record revocations and reserve slots.
- The approval/reject CAS still compares only `state`, not the pause id; that needs a multi-field conditional
  write in the storage layer.
- The remaining ~24 private delegators in `GroupConversationService` stay until their reflective tests are ported.

- Second review round: the follow-up cost-ceiling lookup propagates store failures instead of meaning "unlimited"; an
  exhausted `EmbeddedJson` candidate budget is ambiguous, not a unique match; an ABORT no-result task turn stops the
  following waves; a blank task result is no result; a verification array is accepted only when an element carries a
  `subject`.

**Files:** [`GroupHitlCoordinator.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/GroupHitlCoordinator.java),
[`RestGroupConversation.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestGroupConversation.java),
[`McpHitlTools.java`](../../src/main/java/ai/labs/eddi/engine/mcp/McpHitlTools.java),
[`HitlAccessGuard.java`](../../src/main/java/ai/labs/eddi/engine/hitl/HitlAccessGuard.java),
[`HitlTimeoutHandler.java`](../../src/main/java/ai/labs/eddi/engine/internal/HitlTimeoutHandler.java),
[`GroupLifecycleOps.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/GroupLifecycleOps.java),
[`TaskForceEngine.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/TaskForceEngine.java),
[`NegotiationEngine.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/NegotiationEngine.java),
[`EmbeddedJson.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/EmbeddedJson.java),
[`hitl.md`](../hitl.md), [`group-conversations.md`](../group-conversations.md),
[`passing-context-information.md`](../passing-context-information.md)
