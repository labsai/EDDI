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
