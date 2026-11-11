## 🔒 fix(groups): group-engine hardening — linear option parsing, no created-agent recruits, closed nested children (2026-11-11)

**Repo:** EDDI (`fix/group-engine-hardening`)

### What changed and why

Seven findings from a security review of the group engine, fixed as small, local
changes so they merge cleanly with the open group-signing stack.

1. **Vote options parse in linear time (high, availability).** `VoteTallyEngine`
   read `Option X: …` lines from the newest SYNTHESIS with a whole-text
   MULTILINE pattern ending in `(.+?)\s*[*_]{0,2}\s*$`: its whitespace runs split
   a long run of spaces every possible way, so `"Option A: x" + 800 spaces + "y"`
   took two seconds and 4,000 spaces did not finish in 500. A prompt-injected or
   HUMAN synthesizer (up to 100,000 characters) stalled a node on every voter
   prompt. The synthesis is now read line by line, lines over 1,000 characters
   are skipped, at most 32 options are read, the pattern uses horizontal
   whitespace and a greedy tail (trailing emphasis is stripped in Java), and each
   match runs through the new `BoundedRegex.firstMatch` (deadline) as a second
   line of defence. Captures for every existing shape are unchanged; a label on a
   line of its own still takes the next non-blank line as its text.
2. **Planner output parses in linear time (medium, availability).**
   `TaskListParser`'s markdown tier had the same shape (`(.+?)(?:\s*[:—–-]\s+(.+))?$`)
   and its assignment pattern `(.+?)\s*\)` let three whitespace runs split one
   run of spaces (`"(assigned to" + 200 spaces + "x"`: 1.5 s, ~N^3.4). The text is
   capped at 100,000 characters, read line by line through `BoundedRegex`, the
   title/description split is done in Java, and the assignment captures
   `([^)]*)` up to the first `)`. Reviewed for the same shapes and found linear
   or regex-free: `DebateVerdictParser`, `ConvergenceDetector`,
   `AbstentionDetector`, the facilitator's move parsing (all Jackson plus the
   bounded `EmbeddedJson` scan), and `VoteTallyEngine.OPTION_LABEL`.
3. **No vote stuffing through created agents (medium).** `recruit_agent` and the
   facilitator's RECRUIT move now refuse an id on the discussion's
   `createdAgentIds` and any agent whose configuration carries a
   `dynamicOrigin` (new `CreatedAgentRecruitGuard`; an unreadable configuration
   counts as created). A member could otherwise create a sub-agent with a system
   prompt it wrote and recruit it as a full-weight voter that counts toward
   quorum. Checked after the USE check, so the refusal reveals nothing about an
   agent the owner may not use.
4. **VERIFY no longer has to fail open; judge input is built in one pass;
   member text is fenced in engine prompts (medium).**
   - New `taskListConfig.onUnparseableVerdict`: `PASS` (default — the documented
     behaviour before this change) or `FAIL` (recommended), which fails the tasks
     an unreadable or ambiguous verdict left unsettled and reads a verdict entry
     without `passed` as not passed.
   - `ConvergenceDetector.buildJudgeInput` substituted `{previousRound}` and then
     `{currentRound}` with chained `replace` calls, so a member's literal
     `{currentRound}` in the previous round got the current round spliced into
     it. The template is now split once and the rounds concatenated.
   - Member-authored text re-fed to engine prompts sits in a ` ``` ` fence (inner
     fences broken) under `QUOTED_CONTENT_NOTICE`: VERIFY results, EXECUTE
     dependency results, the bid prompt's task descriptions, the convergence and
     verdict judges, the facilitator's latest-contribution excerpt, the dissent
     prompt and the window summarizer's input. New helpers
     `PeerContentGuard.fenced` (breaks fences, does not cut — a verifier must see
     the whole result) and `withQuotedNotice`.
5. **Nested child discussions are closed (medium-low).** Each GROUP-member turn
   starts a fresh child that nothing ever closed: a COMPLETED child defers its
   ephemeral-agent cleanup to a close, so its dynamic agents stayed deployed, and
   its owner could `continue` it after the team budget was exhausted (team
   admission and charging run at depth 0 only). `MemberTurnExecutor` now closes
   the child in a `finally` once its answer has been read (best-effort, logged),
   and `continueDiscussion` / `followUpWithMember` refuse a discussion with
   `depth > 0` (service: `GroupDiscussionException`; REST: 409 with a clear
   message; streaming: an error event). Nothing in the engine continues a child —
   nested pauses are cancelled, crash recovery resumes, it does not continue.
6. **Schedule context keys are reserved (low).** `scheduleFireId` and
   `scheduleMaxCostPerFire` joined `ClientContextGuard.RESERVED_KEYS`. A client
   sending a fresh `scheduleFireId` each turn reset every `window: "RUN"` tool
   quota. The schedule executor calls the conversation service directly (no strip
   runs on that path) and writes both keys into the fire's input, so scheduled
   fires keep them. `AgentTestRunner.toContext` now strips the reserved keys too, so an
   editor-authored test case cannot set `groupId`, `delegationDepth` or
   `dynamicAgentConfig`.
7. **`maxCreatedAgentsPerDiscussion` holds across parallel members (low).** Each
   member checked the created-agent snapshot it was handed at dispatch.
   `CreateSubAgentTool` now reserves a slot on the live discussion under
   `dynamicTrackingMutex` before setup (`GroupConversation.tryReserveAgentCreation`),
   commits the id to `createdAgentIds` at once, and releases the slot when setup
   fails.

### Design decisions

- `onUnparseableVerdict` defaults to `PASS` because `docs/group-conversations.md`
  documented the auto-verify as intended; the docs and the Platform Operator now
  recommend `FAIL`.
- Fencing is applied in the data handed to the templates (VERIFY/EXECUTE) and at
  the end of each prompt builder, one call each, to stay out of the way of the
  group-signing stack's rewrite of the same classes.
- A committed creation goes on the discussion's `createdAgentIds` immediately
  rather than at the end of the creator's turn, so it keeps its slot and is
  cleaned up even if that turn never completes; the end-of-turn merge already
  de-duplicates.

**Files:** [`VoteTallyEngine.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/VoteTallyEngine.java),
[`BoundedRegex.java`](../../src/main/java/ai/labs/eddi/engine/agenttests/BoundedRegex.java),
[`TaskListParser.java`](../../src/main/java/ai/labs/eddi/engine/internal/TaskListParser.java),
[`CreatedAgentRecruitGuard.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/CreatedAgentRecruitGuard.java),
[`RecruitAgentTool.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/RecruitAgentTool.java),
[`FacilitatorEngine.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/FacilitatorEngine.java),
[`TaskForceEngine.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/TaskForceEngine.java),
[`ConvergenceDetector.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/ConvergenceDetector.java),
[`PeerContentGuard.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/PeerContentGuard.java),
[`MemberTurnExecutor.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/MemberTurnExecutor.java),
[`GroupConversationService.java`](../../src/main/java/ai/labs/eddi/engine/internal/GroupConversationService.java),
[`ClientContextGuard.java`](../../src/main/java/ai/labs/eddi/engine/security/ClientContextGuard.java),
[`AgentTestRunner.java`](../../src/main/java/ai/labs/eddi/engine/agenttests/AgentTestRunner.java),
[`CreateSubAgentTool.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/CreateSubAgentTool.java),
[`GroupConversation.java`](../../src/main/java/ai/labs/eddi/configs/groups/model/GroupConversation.java);
docs: [`group-conversations.md`](../group-conversations.md), [`passing-context-information.md`](../passing-context-information.md),
[`langchain.md`](../langchain.md), [`configuration-reference.md`](../configuration-reference.md).

```decision-log
| 2026-11-11 | `taskListConfig.onUnparseableVerdict` defaults to PASS, FAIL recommended | VERIFY auto-verified every task when the verdict was unreadable | Defaulting to FAIL: the fail-open was documented behaviour, so flipping it would break existing groups silently |
```
