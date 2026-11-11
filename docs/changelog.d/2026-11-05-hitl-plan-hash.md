## ✨ feat(hitl): approval bound to the exact plan — `planHash` (2026-11-05)

**Repo:** EDDI (`feat/hitl-plan-hash`)

### What changed and why

A HITL approval approved "this pause", but nothing tied it to the exact arguments or actions that then ran. Every pause now gets a **plan hash** when it is committed — inspect → plan hash → apply only with that hash:

- **What is hashed** ([`HitlPlanHash`](../../src/main/java/ai/labs/eddi/engine/hitl/HitlPlanHash.java)): SHA-256 over a canonical JSON document, reported as `v1:` + 64 hex. A tool pause covers the conversation, agent and version, the batch's `pauseEpoch`, a per-pause secret nonce, and every pending call in order (`callId`, `toolName`, `source`, `argsTruncated`, canonicalized raw arguments, request fingerprint when pinned). A rule (turn-level) pause covers the bookmark (pause id, workflow, task index) and the paused step's actions without `PAUSE_CONVERSATION`. A group pause covers the paused phase's transcript, the tasks awaiting approval and the synthesized answer.
- **Canonicalization**: keys sorted by UTF-16 code unit, arrays in order, numbers by value (`1` = `1.0` = `1e0`), strings as parsed (escapes decoded, no Unicode normalization); arguments that are not exactly one JSON document (duplicate keys, anything after the first value) are hashed as their exact text. The existing `JacksonCanonicalizer` was not reused because it keeps Jackson's number formatting (`1` ≠ `1.0`).
- **Stored with the pause**: `hitlPlanHash` on the conversation (cleared with the bookmark) and `planHash` + `planNonce` on the pending tool batch. Computed in `ConversationHitlService#populateHitlTimeoutBookmark`, i.e. after the turn's secret scrub rewrote the batch and before the store, so it covers exactly what is persisted. The nonce is never served (approver projection nulls it, names-only projection never copies it, the Mongo and PostgreSQL tail reads leave it on the server).
- **Checks** ([`HitlPlanBinding`](../../src/main/java/ai/labs/eddi/engine/hitl/HitlPlanBinding.java), one rule for both surfaces): a decision whose `planHash` is not the pause's → `409` before the CAS and again on the snapshot the resume runs; the resume re-derives the hash from the loaded plan and refuses (`409`, `WARN hitl.plan.changed`, pause restored) when it no longer matches; `ToolLoopResumer` re-derives it once more immediately before an approved call runs (`NOT_EXECUTED`, "the plan changed after it was approved").
- **Compatibility**: a decision without `planHash` is accepted and logged (`hitl.plan.not_presented`) unless strict binding is on — `hitlConfig.requirePlanHash` (agent or group) or `eddi.hitl.plan-hash.strict=true` — then it is a `400` and the pause is not consumed. `system:*` decisions are exempt. Pauses committed before this change have no hash and are decided as before, also under strict binding (`hitl.plan.legacy`).
- **A hash that cannot be computed fails closed under strict binding**: `HitlPlanHash.commit` never throws, so a hashing bug cannot stop pauses from committing, but the pause it could not hash stores the marker `"unavailable"` (conversation `hitlPlanHash` and batch `planHash`; it persists through both stores like any hash) instead of null. A null would have made it indistinguishable from a legacy pause, which strict binding accepts unbound. `HitlPlanBinding.check` refuses a human decision on a marked pause under strict binding (`409`, the pause is restored as for a mismatch). Without strict binding it accepts the decision, logs `WARN hitl.plan.unavailable` and records it as `PLAN_HASH_UNAVAILABLE`. `approval-status` (REST, MCP) and Slack cards serve `""` for the marker, and it never matches. Group plans are derived at decision time, not stored, so a failed derivation already fails the approve.
- **Surfaces**: REST `approval-status` (`planHash`) and the resume/approve bodies (`HitlDecision.planHash`); MCP `get_approval_status` / `get_group_approval_status` report it, `resume_conversation` / `approve_group_phase` take an optional `planHash` (`PLAN_CHANGED`, `PLAN_HASH_REQUIRED`); Slack buttons carry it as a fourth value field and the edit/reject modals keep it in their metadata, and a card whose plan is no longer the pending one is replaced by a "Not applied — the request has changed" notice without buttons; the Manager's `lib/hitl-pause-binding.ts` sends the shown plan from every approval surface, and the operator card sends it too.
- **Audit**: `hitl.approval` entries carry `planHash` and `planBinding` (`MATCHED` / `NOT_PRESENTED` / `SYSTEM` / `LEGACY_UNHASHED` / `PLAN_HASH_UNAVAILABLE`); every task a resume runs carries `input.hitlPlanHash`; `hitl_approved` trace entries carry `planHash`.
- **Platform Operator**: system prompt documents plan binding and strict mode; revision 60 → 61.

**Docs:** [hitl.md → Plan Binding](../hitl.md#plan-binding-planhash), [hitl-review-and-audit.md](../hitl-review-and-audit.md), [hitl-approval-context.md](../hitl-approval-context.md), [audit-ledger.md](../audit-ledger.md), [mcp-server.md](../mcp-server.md), [configuration-reference.md](../configuration-reference.md), [cryptography.md](../cryptography.md).

### Design decisions

- **Strict mode at two levels** (agent/group `hitlConfig.requirePlanHash` and deployment `eddi.hitl.plan-hash.strict`), either one enabling it: a designer protects high-stakes agents without breaking other clients; an operator turns it on everywhere once all clients send the hash. A failed config read counts as strict (fail closed).
- **Group plans are derived, not stored**: an `AWAITING_APPROVAL` discussion cannot change except through the approval, so the read-time hash is the stored state's hash.
- **Amended arguments are not re-hashed**: the hash binds what the reviewer was shown; the amendment is the reviewer's own recorded change.
- **The tool-call plan omits the pause id** (it uses the batch's `pauseEpoch` instead) so the tool loop can re-derive it after the bookmark is cleared.

### What's next

- Strict binding could become the default once every first-party client has shipped a release that sends the hash.

```decision-log
| 2026-11-05 | HITL decisions bind to a canonical SHA-256 plan hash; strict mode opt-in per agent/group or per deployment | Approvals were bound to a pause, not to the exact arguments/actions that ran | Reusing JacksonCanonicalizer (number formatting not canonical); a deployment-HMAC instead of a per-pause nonce (key rotation and cluster distribution for no gain); storing group plan hashes |
```
