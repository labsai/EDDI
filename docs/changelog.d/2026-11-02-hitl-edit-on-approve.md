## ✨ feat(hitl): edit-on-approve everywhere and the HITL decision audit contract (2026-11-02)

**Repo:** EDDI (`feat/hitl-edit-on-approve`)

### What changed and why

An approver can now approve **with an edit**, and every HITL decision is recorded in the audit ledger in one typed
shape that analytics can rely on.

- **Config** — `hitlConfig.review` on agents ([`HitlReviewConfig`](../../src/main/java/ai/labs/eddi/configs/hitl/model/HitlReviewConfig.java))
  and groups ([`GroupHitlReviewConfig`](../../src/main/java/ai/labs/eddi/configs/hitl/model/GroupHitlReviewConfig.java)),
  and `toolApprovals.rules[].approvalCategory`. All optional; a config without them behaves exactly as before.
  Validated at save time by [`HitlReviewConfigValidation`](../../src/main/java/ai/labs/eddi/configs/hitl/HitlReviewConfigValidation.java).
- **Audit contract** — every `hitl.approval` entry is now written by
  [`HitlApprovalAudit`](../../src/main/java/ai/labs/eddi/engine/hitl/audit/HitlApprovalAudit.java) with `schema: 2`:
  the legacy fields stay, and the decision gains `decisionId`, `pauseId`, `pausedAt`, `decidedAt`, `latencyMs`,
  `channel` (`REST`, `MANAGER` from the `X-EDDI-Client: manager` header, `MCP`, `SLACK`, `TIMEOUT`, `SYSTEM`),
  `automationReason`, `approvalCategory`/`approvalCategorySource`, `reasonCode` and a `units[]` list — one unit per
  answer, tool call, phase or task, each with its `decisionOutcome` (`APPROVED`, `APPROVED_EDITED`, `REJECTED`,
  `AUTO_APPROVED`, `AUTO_REJECTED`, `ABORTED`) and edit metrics. `HitlApprovalAudit.parse` reads schema 1 and 2 into
  [`HitlDecisionUnit`](../../src/main/java/ai/labs/eddi/engine/hitl/audit/HitlDecisionUnit.java)s, and the CDI event
  [`HitlDecisionRecorded`](../../src/main/java/ai/labs/eddi/engine/hitl/audit/HitlDecisionRecorded.java) is fired
  (async) after each write. The four writers were uneven before: a regular cancel had no agent or user, a group
  cancel had no `decidedBy`, a group decision had no phase — all fixed.
- **Reason codes** — `reasonCode` on a decision and on `toolDecisions[]`, checked before the pause is consumed by
  [`HitlDecisionValidator`](../../src/main/java/ai/labs/eddi/engine/hitl/review/HitlDecisionValidator.java)
  against `review.reasonCodes` or the built-in vocabulary. `approval-status` lists the vocabulary and the category in
  `pauseDetails`.
- **Approve a RULE pause with an edited answer** — `editedAnswer` on the resume body, for agents with
  `review.allowAnswerEdits`. The draft is kept with the pause (also without `onRejection: REVISE`), checked before the
  CAS (not blank, `maxEditChars`, no control characters, a JSON object for a `convertToObject` task), and applied by
  the LLM task in place ([`LlmAnswerEditor`](../../src/main/java/ai/labs/eddi/modules/llm/impl/LlmAnswerEditor.java)):
  the answer's own output item and data keys, nothing after it, no `postResponse`, no task re-run. Under
  `screenEdits: OUTPUT_GUARDRAILS` the task's output guardrails screen the edit (`reprompt` acts as `block`); a blocked
  edit or a changed configuration pauses the turn again on the unchanged draft, records `hitl.edit.refused` and shows
  `lastEditRefused` on the next pause. `approval-status` serves the redacted draft as `pauseDetails.editableAnswer`.
- **Group edits** — `taskDecisions` on the group approve body (`{verdict, note, editedResult, reasonCode}` per task,
  exclusive with `taskApprovals`) and `decision.editedAnswer` on a PHASE pause after a SYNTHESIS phase, for groups with
  `review.allowResultEdits` ([`GroupReviewEditor`](../../src/main/java/ai/labs/eddi/engine/internal/groups/GroupReviewEditor.java)).
  An edited result replaces `TaskItem.result` (dependents and the summary read it) and is noted in
  `SharedTaskList.reviewerEdits`; a synthesis edit is appended as an unsigned `SYNTHESIS` entry (speaker
  `hitl:<decidedBy>`, `errorReason: "reviewer-edit"`) — signed entries are never rewritten, and the signing guard skips
  reviewer entries.
