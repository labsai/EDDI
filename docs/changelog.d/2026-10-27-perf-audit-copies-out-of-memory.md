## perf(audit): the stored conversation no longer repeats the prompt and response the ledger holds (2026-10-27)

**Repo:** EDDI (`perf/audit-copies-out-of-memory`)

### What changed and why

With auditing on, `LlmTask` writes `audit:compiled_prompt` (system prompt plus prompt) and `audit:model_response` to every step so `LifecycleManager` can build the ledger entry. Nothing removed them afterwards, so the full text lived in the conversation document too. On a measured 1.6 MB, 20-step conversation the compiled prompt alone was 325 KB (20%) and the model response 39 KB. Large documents slow every read and write of a conversation, and a deployment whose link to MongoDB is bandwidth-limited felt it directly.

- **Release after hand-over.** `Conversation` (say and resume paths) calls `TurnAuditBuffer.releaseLedgerOwnedEvidence` once the turn's buffer has been flushed and the ledger accepted every entry. Within the turn nothing changes: the entries are built, buffered and redacted exactly as before; the keys are removed afterwards, before the conversation is stored.
- **Only what was accepted.** `AuditLedgerService.submit` now returns whether the entry was queued, and the collectors `ConversationService` and `ConversationHitlService` install implement the new `IAuditEntryCollector.Acknowledging`. `TurnAuditBuffer.flush` returns false if any entry was refused (full queue) or any submit threw, and then the evidence stays in the stored step. A plain collector (tests) counts as accepting unless it throws.
- **Paused turns keep it.** A turn that ends `AWAITING_HUMAN` resumes from the stored step, so the keys stay until the resume that completes the turn.
- **Deliberately kept:** `audit:tool_calls`, `audit:token_usage`, `audit:cost`, `audit:model_name` and the guardrail records. Agent tests read tool calls, cost and model from the stored step (`TurnObservation.fromSnapshot`), the `/v1` adapter reads token usage, and the history replay reads the guardrail records. They are small by comparison.
- **No consumer needed the removed keys.** Searched the Manager UI, Chat UI, REST and MCP surfaces, conversation log, exports and GDPR export: the only reader of the two keys is `LifecycleManager.buildAuditEntry`, inside the turn. The text is read from the ledger (`GET /auditstore/{conversationId}`). Documented in [`audit-ledger.md`](../audit-ledger.md).
- **Auditing off:** the keys are never written and no code path changes.
- **Already-stored conversations** keep what they hold; only new turns are slimmer.

**Files:** [`TurnAuditBuffer.java`](../../src/main/java/ai/labs/eddi/engine/audit/TurnAuditBuffer.java), [`IAuditEntryCollector.java`](../../src/main/java/ai/labs/eddi/engine/audit/IAuditEntryCollector.java), [`AuditLedgerService.java`](../../src/main/java/ai/labs/eddi/engine/audit/AuditLedgerService.java), [`Conversation.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/Conversation.java), [`ConversationService.java`](../../src/main/java/ai/labs/eddi/engine/internal/ConversationService.java), [`ConversationHitlService.java`](../../src/main/java/ai/labs/eddi/engine/internal/ConversationHitlService.java); tests `ConversationAuditEvidenceReleaseTest`, `TurnAuditBufferTest`, `AuditLedgerServiceTest`.

```decision-log
| 2026-10-27 | The prompt and response are removed from the stored step once the ledger accepted the turn's entries; tool calls, usage, cost, model name and guardrail records stay | The ledger is the system of record and holds the full text; the copy was a fifth of the document. Agent tests, cost metering and history replay read the small keys back from the stored step | A config switch (no consumer needs the text in memory); a hash/reference in place of the text (the ledger is addressable by conversation id and step index already); stripping `audit:tool_calls` too (agent tests read it from the stored step) |
```
