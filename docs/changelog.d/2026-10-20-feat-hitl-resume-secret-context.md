## feat(hitl): secret context on resume (2026-10-20)

**Repo:** EDDI (`feat/hitl-resume-secret-context`)

### What changed and why

A per-turn credential sent as secret context (`secret: true`) is replaced by a placeholder when a turn pauses, so every task after an approval that needed it (an approved HTTP tool call acting as the user) failed: a pinned call was refused as "the request changed after it was approved", an unpinned one failed at execution. `POST /agents/{conversationId}/resume` now accepts an optional `context` map in the same shape as a say request. Only entries marked `secret: true` whose key was a secret in the paused turn are accepted; anything else is a 400 before the pause is consumed. The values replace the placeholder for the resumed turn only and go through the same scrubbing as on `say` (shared `registerSecretContext`, `scrubSecretContextValues`, `TurnAuditBuffer` redaction), so the stored step holds the placeholder again afterwards.

Files: [`HitlDecision.java`](../../src/main/java/ai/labs/eddi/engine/lifecycle/model/HitlDecision.java) (write-only `context`), [`ConversationHitlService.java`](../../src/main/java/ai/labs/eddi/engine/internal/ConversationHitlService.java) (pre-CAS validation), [`Conversation.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/Conversation.java) (injection and scrub), [`hitl.md`](../hitl.md).

### Design decisions

- Timeout, Slack, MCP and group resumes pass no context and are unchanged. MCP deliberately does not get it: tool arguments are logged and audited.
- Approval binding is not weakened: headers are fingerprinted redacted, so an identical re-supplied token matches (as does a rotated one in a credential header such as `Authorization`); a different value in the URI, query, body or an unrecognised header changes the fingerprint and the call is refused (fail closed). Documented in `hitl.md`.
