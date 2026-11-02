## ✨ feat(hitl): edit-on-approve everywhere and the HITL decision audit contract (2026-11-02)

**Repo:** EDDI (`feat/hitl-edit-on-approve`)

### What changed and why

An approver can now approve **with an edit**, and every HITL decision is recorded in the audit ledger in one typed
shape that analytics can rely on.

- **Config** — `hitlConfig.review` on agents ([`HitlReviewConfig`](../../src/main/java/ai/labs/eddi/configs/hitl/model/HitlReviewConfig.java))
  and groups ([`GroupHitlReviewConfig`](../../src/main/java/ai/labs/eddi/configs/hitl/model/GroupHitlReviewConfig.java)),
  and `toolApprovals.rules[].approvalCategory`. All optional; a config without them behaves exactly as before.
  Validated at save time by [`HitlReviewConfigValidation`](../../src/main/java/ai/labs/eddi/configs/hitl/HitlReviewConfigValidation.java).
