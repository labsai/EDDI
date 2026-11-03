## 🔒 fix(engine): agent-findings review of engine/internal — resume USE check, undeploy overflow, synthesis approval under the cost ceiling (2026-11-03)

**Repo:** EDDI (`fix/agent-findings-engine-internal`)

### What changed and why

A batch of 41 scanner findings against `engine/internal` (REST endpoints, conversation
and group authorization, data exposure) was traced through the current code. 26 had
already been fixed by #1161, #1188, #1193 and #1207; the real remainder is fixed here.

- **Resume re-checks USE for an owner.** `HitlAccessGuard.requireConversationResumeAccess`
  is `requireConversationHitlAccess` plus, for a caller who is neither admin nor approver,
  the turn check `say` applies (`ConversationAccessGuard.requireConversationTurnAccess`):
  ownership outlives a revoked share, the right to run the agent does not. Used by
  `POST /agents/{conversationId}/resume` and MCP `resume_conversation`. Approvers and
  admins decide by role, unchanged — the same split the group approve already makes.
- **MCP `get_approval_status` withholds the compiled prompt** from a caller without VIEW
  on the agent, as the REST `approval-status?detail=full` has since #1193.
- **Single-version undeploy refuses a version below 1** (400). The decrementing loop
  wrapped at `Integer.MIN_VALUE` and never ended; the bulk path was bounded in #1193.
- **A SYNTHESIS phase that runs under the `SYNTHESIZE_NOW` skip-ahead keeps its
  `requiresApproval` gate.** Only the phase that trips the ceiling skips its own gate
  (abandoned work); the synthesis answer is executed work and is published.
- **Member tool-pause recovery** resumes with the detailed projection (the summary one
  dropped the dynamic-agent tracking keys, so agents created while resuming escaped
  cleanup) and harvests the resumed run's cost into the ledger before any return,
  including a re-pause — the next member's ceiling check saw a stale total.
- **`GET /administration/logs/history` with `limit` ≤ 0** reads the default page of 100.
  Both log stores treat a non-positive limit as "no limit" and collected the whole
  history onto the heap.

Verdicts: 6 fixed, 26 already fixed, 1 false positive (the vote-option regex: a
400,000-character worst-case synthesis parses in well under the test timeout on the
current JVM, so no quadratic cost could be shown), 3 accepted risk (the auto-approval counter is the
documented consecutive no-progress guard, total pauses are capped by `maxPausesPerTurn`;
a resume deploys its pinned version on demand, documented in `hitl.md`; the agent quota's
bounded concurrent overshoot, documented on `enforceAgentQuota`), 5 need a decision
(contextScope for the phase-specific prompt variables, a durable record of follow-up
creations after a timeout, explicit unretain propagation, an atomic discussion-wide
creation reservation, a configured-task cap).

**Files:** [`HitlAccessGuard.java`](../../src/main/java/ai/labs/eddi/engine/hitl/HitlAccessGuard.java),
[`RestAgentEngine.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestAgentEngine.java),
[`McpHitlTools.java`](../../src/main/java/ai/labs/eddi/engine/mcp/McpHitlTools.java),
[`RestAgentAdministration.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestAgentAdministration.java),
[`GroupConversationService.java`](../../src/main/java/ai/labs/eddi/engine/internal/GroupConversationService.java),
[`MemberTurnExecutor.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/MemberTurnExecutor.java),
[`RestLogAdmin.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestLogAdmin.java),
[`log-administration.md`](../log-administration.md), [`hitl.md`](../hitl.md),
[`workspaces.md`](../workspaces.md), [`group-conversations.md`](../group-conversations.md)
