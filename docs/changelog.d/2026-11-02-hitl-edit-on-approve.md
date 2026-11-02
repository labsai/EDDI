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
- **Slack** — approval cards of regular conversations gain "✏️ Edit & approve" and "Reject with reason…"
  ([`SlackHitlEditModal`](../../src/main/java/ai/labs/eddi/integrations/slack/SlackHitlEditModal.java)), opened as
  modals with `views.open`. A TOOL_CALL card offers Edit for a single call whose redacted arguments fit Slack's 3000
  characters; edited arguments are merged server-side against the raw ones
  ([`SlackRedactedArgumentsMerge`](../../src/main/java/ai/labs/eddi/integrations/slack/SlackRedactedArgumentsMerge.java)),
  so redacted secrets never travel through Slack and cannot be edited there. A RULE card offers Edit only with the new
  integration setting `platformConfig.hitlShareRuleDrafts=true`, for agents that allow edits and drafts without
  secrets. `view_submission` re-runs every check (approver, workspace, card, pause) and is validated synchronously
  through `IConversationService.validateHitlDecision`, so errors show in the modal. Group cards keep Approve/Reject.
  `SlackHitlSupport.buildApprovalBlocks` keeps its signature; the new overload takes the card's edit controls and keeps
  the `actions` block last.
- **Manager** — the approval banner offers "Edit the answer before approving" on a RULE pause whose
  `pauseDetails.editableAnswer` is present (textarea with the draft, a counter against `maxChars`, JSON validation for
  a JSON answer, the original one click away), a reason-code select for rejections and edits, and a notice when the
  engine refused the last edit; the group transcript marks a reviewer's SYNTHESIS entry. Every Manager decision sends
  `X-EDDI-Client: manager`. New keys under `hitlReview.*` in all 11 locales.
- **Docs** — new page [HITL Review & Audit](../hitl-review-and-audit.md) (config, edit semantics per surface, Slack
  modals, the full audit contract with the schema-1 mapping, the reason-code vocabulary, a worked GitHub-reply
  example), linked from `hitl.md`, `group-conversations.md`, `mcp-server.md` and `SUMMARY.md`. The operator and MCP
  follow-up is in [`planning/operator-followups/hitl-edit-on-approve.md`](../../planning/operator-followups/hitl-edit-on-approve.md).

### Contract for the features built on this branch

- **Approval analytics** reads `hitl.approval` entries only through `HitlApprovalAudit.parse(AuditEntry)` →
  `List<HitlDecisionUnit>`, and observes `HitlDecisionRecorded(String auditEntryId, List<HitlDecisionUnit> units)`
  with `@ObservesAsync`. Values of `DecisionOutcome`, `HitlChannel`, `UnitKind` and `EditSummary.Kind` are only ever
  added. HUMAN_TURN resolutions have no units and no outcome.
- **Approval card context** extends the Slack card through `SlackHitlSupport.buildApprovalBlocks`, whose signatures
  stay as they are; the `actions` block stays the card's last block.

### Not in this change

- The Manager has no UI for group task-result or synthesis edits yet (REST and MCP have them); Slack cards of group
  discussions keep Approve/Reject.
- A note typed in a Slack modal is recorded but does not drive `onRejection: REVISE`.
- The approval category of a rule or group pause is derived from the configuration at decision time (the pinned
  agent version for agents; the current group version for groups) rather than stamped at pause time.

```decision-log
| 2026-11-02 | One `hitl.approval` entry per decision event with a `units[]` list (answer, tool call, phase, task) and `schema: 2`; legacy fields kept | Analytics needs per-unit outcomes, categories and edit sizes; existing readers need the old fields | One entry per unit (breaks the one-entry-per-decision shape, multiplies ledger sequence numbers); a separate store written alongside the ledger |
| 2026-11-02 | An approved edit of a rule-pause answer replaces only the draft's own output item and data keys; nothing re-runs; a guardrail-refused edit re-pauses on the unchanged draft | Re-running later tasks would re-trigger the pause rule; a refused edit must not consume the human's pause | Truncating and re-running the rest of the turn (as REVISE does); screening before the CAS (model calls on the request thread and inside Slack's 3-second window) |
| 2026-11-02 | A group SYNTHESIS edit is appended as an unsigned reviewer entry (`hitl:<decidedBy>`, `errorReason: reviewer-edit`), reusing the SYNTHESIS type | Signed transcript entries must never be rewritten; the existing "last synthesis" extraction then needs no change and older pods deserialize it | A new transcript entry type; rewriting the signed entry in place |
| 2026-11-02 | Slack edits of tool arguments are merged server-side against the raw arguments; redacted values cannot be edited from Slack | Slack only ever sees redacted arguments; a value left as shown must keep its raw secret, and a marker must never become an argument | Sending raw arguments to Slack; refusing every edit of a call that has any redacted value |
| 2026-11-02 | `HitlDecisionRecorded` is fired with `fireAsync` through `Arc.container()` from the plain audit writer | Nothing a consumer does may run on the decision's critical path; the HITL collaborators are plain classes built in their facades' constructors | A synchronous event; new CDI constructor parameters on the facades |
```
