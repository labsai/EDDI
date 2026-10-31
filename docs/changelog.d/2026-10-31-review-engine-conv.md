## 🐛 fix(engine): conversation engine, HITL and memory findings from the external reviews (2026-10-31)

**Repo:** EDDI (`fix/review-engine-conv`)

Verified findings from the CodeRabbit security review and the full code review against `01c3250`, for the conversation engine (`engine/internal`), the HITL services and conversation memory (`engine/memory`).

### What changed and why

**Conversation state can no longer be resurrected or overwritten blindly**

- The agent-timeout watchdog and the turn error path wrote `EXECUTION_INTERRUPTED` / `ERROR` with an unconditional store write. An end only raises the cooperative-cancel flag, which a hung model call never sees, so ending a stuck conversation was undone by the watchdog — and `EXECUTION_INTERRUPTED` auto-recovers on the next say, so the ended conversation was drivable again and invisible to the ENDED-only retention sweep. Both are now a compare-and-set from the state they read, never over `ENDED` (`ConversationStepRunner.transitionUnlessEnded`; both stores already had `compareAndSetState`).
- The admin `POST …/resetState` read the state and then wrote READY: `IConversationService.resetConversationState` now takes the expected state and is a compare-and-set; a lost race answers `409`.
- A cancelled say ends its pipeline through the `STOP_CONVERSATION` path, so the caller was told `ENDED` while the store recorded `EXECUTION_INTERRUPTED`. It is now reported as what the store holds (`ConversationService.reportCancelledTurnAsInterrupted`).

**HITL**

- An `ABORT` timeout (and the pending-approval retention sweep in `HitlCrashRecoveryObserver`) used the full cancel and was not bound to its pause: a fire claimed just before a resume stopped the resumed turn, or aborted a newer pause. Both now use the paused-only cancel bound to the pause id (`IConversationService.cancelPausedConversation(id, by, pauseId)`), which re-checks the pause after its compare-and-set and puts back a pause it did not mean. The group surface is left to the groups work.
- Resume did not apply the GDPR Art. 18 processing restriction that start and say apply; a restricted owner could drive a resumed turn (including a rejection-note revision). It now refuses with the pause restored and its timeout still armed.
- A tool pause's timeout policy came from the governing rule of the *matched* calls only, so an `AUTO_APPROVE` rule for one endpoint auto-approved an unmatched gated call bundled into the same model message. A call that no policy-stating rule covers now contributes the policy it would inherit, strictest wins.
- `getAgent` deployed any missing runtime on demand, so a resume (or a say) brought back a version that was intentionally undeployed, bypassing the deploy endpoint's EDIT, space-reference and quota checks — contrary to `docs/hitl.md`. A version whose deployment record says `undeployed` is no longer deployed on demand.
- The approver projection stripped the revision candidate (frozen prompt + tool results) from active steps but not from the redo cache an undo fills.

**Memory, retention and reads**

- The raw read `GET /conversationstore/conversations/{id}` and `approval-status?detail=full` served `audit:compiled_prompt` — the agent's rendered system prompt — to an owner who may only USE the agent. Both now strip `audit:*` (keeping `audit:token_usage`) unless the caller has VIEW on the agent, as the detailed view and MCP `read_audit_trail` already did (`ConversationMemoryUtilities.stripAuditData`).
- The retention sweep deleted the snapshot without the memory checkpoints (copies of the properties) and the HITL tool journal, removing the only link a later user erasure had to them. It now runs the same dependent cascade as a permanent delete; and both paths keep the snapshot and descriptors when any dependent delete fails (`ConversationPurgeService.deleteDependentData`), or when the vault is unavailable while the conversation references auto-vaulted secrets, instead of orphaning the copies.
- The conversation listing's "search matches nothing → list everything" fallback decided by searching the whole store, an existence oracle over other users' conversations. It is now decided within the caller's own records.
- Mid-turn memory checkpoints copied properties before the turn-end secret-context scrub, persisting a secret context value a property instruction had copied. The checkpoint copy is now scrubbed with the same thresholds (`MemorySnapshotService.withoutSecretContextValues`).
- `SecretValueScrubber` decoded serialized JSON only in exact mode; in substring and whole-token mode a secret with a JSON-escaped character survived inside tool-call arguments. All modes now decode embedded JSON.
- Attachment uploads to a legacy conversation used the descriptor's (null) owner as the quota identity, exempting them from the per-user quota; they now use the owner the access guard resolved.
- An `Idempotency-Key` retry was served a turn's answer from the grace cache even when the turn's persist then failed; a failed persist now drops it (`TurnIdempotencyService.forgetUnpersisted`).

**Administration**

- `undeployThisAndAllPreviousAgentVersions` counted down from any requested integer (one DB read and one undeploy task per number, plus an "undeployed" record for versions that never existed). The starting version must now be one the agent has (`400` above its current version).
- The agent quota counted only each agent's newest runtime version, so a failed newer version hid a live older one; it now counts any READY version. A deployment-count outage failed open; it now refuses with `503` while an agent limit is in force (`TenantQuotaService.agentCountUnavailable`).
- The live log stream (`/administration/logs/stream`) kept delivering admin-only records after the opening token expired; it now closes at the token's expiry, as the cluster activity stream does.

**Dead code and duplication**

- Removed `SecretValueScrubber.scrubContext`, `ITurnAbandonment` with `ConversationService.abandonInFlightTurn` (only a test called it), and the unused `MemoryKeys.SYSTEM_MESSAGE` / `PROMPT`.
- `ConversationAccessGuard`'s four near-identical owner checks now share one helper.

### Design decisions

- Pause-bound cancels re-verify the pause *after* the state compare-and-set and restore it on a mismatch, before any side effect — the same pattern resume uses — rather than adding a pause-id predicate to both stores' CAS.
- The agent quota fails closed only where a limit applies, so a deployment-store outage never blocks deploys on deployments without an agent quota.
- `getAgent` keeps the on-demand deploy for versions with no deployment record (transient `autoDeploy=false` deploys, never-recorded versions) and on a store error, preserving the cluster/restart behaviour it exists for.

### Deferred

- API-call quota spent on turns refused after admission (duplicate, skipped, ended): needs a refund operation in both quota stores or moving acquisition into the queued turn — a product decision on whether skipped turns count.
- `ConversationService`'s thin private delegators kept for reflective tests (§7): reducing them means rewriting the characterization suites that reach them via `getDeclaredMethod`; not done here.

**Files:** [`ConversationStepRunner.java`](../../src/main/java/ai/labs/eddi/engine/internal/ConversationStepRunner.java), [`ConversationService.java`](../../src/main/java/ai/labs/eddi/engine/internal/ConversationService.java), [`ConversationHitlService.java`](../../src/main/java/ai/labs/eddi/engine/internal/ConversationHitlService.java), [`HitlTimeoutHandler.java`](../../src/main/java/ai/labs/eddi/engine/internal/HitlTimeoutHandler.java), [`HitlCrashRecoveryObserver.java`](../../src/main/java/ai/labs/eddi/engine/hitl/HitlCrashRecoveryObserver.java), [`RestAgentAdministration.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestAgentAdministration.java), [`RestAgentEngine.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestAgentEngine.java), [`RestLogAdmin.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestLogAdmin.java), [`TurnIdempotencyService.java`](../../src/main/java/ai/labs/eddi/engine/internal/TurnIdempotencyService.java), [`RestConversationStore.java`](../../src/main/java/ai/labs/eddi/engine/memory/rest/RestConversationStore.java), [`RestAttachmentUpload.java`](../../src/main/java/ai/labs/eddi/engine/memory/rest/RestAttachmentUpload.java), [`ConversationPurgeService.java`](../../src/main/java/ai/labs/eddi/engine/memory/ConversationPurgeService.java), [`ConversationMemoryUtilities.java`](../../src/main/java/ai/labs/eddi/engine/memory/ConversationMemoryUtilities.java), [`MemorySnapshotService.java`](../../src/main/java/ai/labs/eddi/engine/memory/MemorySnapshotService.java), [`SecretValueScrubber.java`](../../src/main/java/ai/labs/eddi/engine/memory/SecretValueScrubber.java), [`IConversationService.java`](../../src/main/java/ai/labs/eddi/engine/api/IConversationService.java), [`TenantQuotaService.java`](../../src/main/java/ai/labs/eddi/engine/tenancy/TenantQuotaService.java), [`ConversationAccessGuard.java`](../../src/main/java/ai/labs/eddi/engine/security/ConversationAccessGuard.java); docs [`hitl.md`](../hitl.md), [`gdpr-compliance.md`](../gdpr-compliance.md), [`tenant-quotas.md`](../tenant-quotas.md), [`deployment-management-of-agents.md`](../deployment-management-of-agents.md), [`rest-api-reference.md`](../rest-api-reference.md).
