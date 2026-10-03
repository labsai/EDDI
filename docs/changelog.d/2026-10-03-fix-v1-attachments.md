## 🐛 fix(openai,attachments): per-chat /v1 conversations, one stream terminator, OpenAI-shaped errors; MIME false positives and atomic quotas (2026-10-03)

**Repo:** EDDI (`fix/v1-attachments`)

Review 2026-10-02 §4.6, the `/v1` and attachment findings.

### What changed and why

- **`/v1` chat key.** A request naming no chat used to land in one `:default` conversation per user and agent, so every SDK/LangChain chat shared context and one document grew towards MongoDB's 16 MB limit. The key is now, in order: `X-OpenWebUI-Chat-Id`, the new `X-EDDI-Chat-Id` header, `metadata.chat_id`, `user` — and otherwise `h:` plus a SHA-256 of the first system and first user message, which an OpenAI client resends unchanged on every turn of a chat. A history-keyed request without history is an opening turn and always starts a fresh conversation (the earlier one is abandoned, not ended). `eddi.openai-compat.chat-key-fallback=shared` restores the old behaviour. Explicit keys over 128 characters are stored as their hash.
- **Bounded growth.** Optional `eddi.openai-compat.max-conversation-steps` (off) ends an idle mapped conversation and continues the chat in a fresh one; LLM context was already bounded by memory windowing.
- **Streaming terminator.** `OpenAiSseWriter` is synchronized and drops every write after its terminator, so tokens from a pipeline still running after the request timeout can no longer follow `[DONE]` (seen live on 6.5.0 / Postgres).
- **Errors.** An ERROR turn is HTTP 500 `{"error":{…,"code":"agent_error"}}` carrying the failing task's sanitized `taskErrors` digest, and on a stream an OpenAI error event before `[DONE]` — never `_The agent produced no text output._` with `finish_reason: "stop"`. Busy, concurrency-cap and timeout failures stream as error events too. `LlmTask` records `llm:finish_reason` (`length`/`content_filter`) and `/v1` reports it as `finish_reason`. Builds on conversation state only, so it does not depend on #946's top-level turn `error`.
- **MIME false positives.** Text declared as a text type and reading as text is compatible whatever signature it starts with (`BMI,…` CSV, Markdown quoting `%PDF-1.7`); BMP detection needs a plausible header; a PDF header past offset 0 counts only at a line start or after a BOM.
- **Quotas.** Checked and inserted under one lock per scope — `pg_advisory_xact_lock` in a transaction on Postgres, a lease document in `attachments.quota_locks` on MongoDB. Live, 12 concurrent uploads against a limit of 3 stored 12 on 6.5.0 and 3 here, on both backends. New optional per-user quota (`eddi.attachments.max-per-user`, `max-total-bytes-per-user`, off), counted against the conversation owner now recorded on each blob.
- **Metrics:** `eddi.openai.chat_keys{source}`, `eddi.openai.conversations.rolled_over`, `eddi.attachments.quota.rejected{scope}` — on the full metrics dashboard.

**Files:** [`OpenAiConversationBridge.java`](../../src/main/java/ai/labs/eddi/integrations/openai/OpenAiConversationBridge.java), [`OpenAiSseWriter.java`](../../src/main/java/ai/labs/eddi/integrations/openai/OpenAiSseWriter.java), [`MimeValidator.java`](../../src/main/java/ai/labs/eddi/engine/attachments/MimeValidator.java), [`GridFsAttachmentStore.java`](../../src/main/java/ai/labs/eddi/datastore/mongo/GridFsAttachmentStore.java), [`PostgresAttachmentStore.java`](../../src/main/java/ai/labs/eddi/datastore/postgres/PostgresAttachmentStore.java); docs [`open-webui-integration.md`](../open-webui-integration.md), [`attachments-guide.md`](../attachments-guide.md), [`configuration-reference.md`](../configuration-reference.md), [`metrics.md`](../metrics.md).

- **`/v1` `usage` now reports.** The adapter reads `audit:token_usage` from the detailed snapshot, but that snapshot withheld every `audit:` key, so `usage` was never sent (live, on 6.5.0 and on this branch before the fix). `audit:token_usage` — three counts, no prompt text — is now exempt from that denylist; the rest of `audit:*` stays withheld.
- **No internal detail in an unclassified `/v1` failure.** The message of an unexpected exception is logged, not returned to the caller — on the plain path, in a stream's error event, and when a conversation cannot be started. A failed turn still reports its sanitized task digest.
- **`usage` without the audit ledger.** `LlmTask` wrote `audit:token_usage` only with an audit collector attached, so `eddi.audit.enabled=false` silently dropped `/v1` `usage`. The token counts are now recorded either way; the rest of the audit evidence stays behind the collector gate.
- **Mapping rows are swept with their conversation.** The ended-conversation retention sweep (`RestConversationStore.permanentlyDeleteEndedConversationLogs`) now deletes the `(intent, userId)` channel mappings — `/v1` and Slack — that point at a conversation it deletes. A `/v1` client sending only its latest message leaves one mapping per distinct message, and nothing removed them.

### Next

- Same-opening collision (one user, two chats with an identical first system + user message, including few-shot prompts) stays documented, not fixed: nothing in the request separates the chats until their histories diverge. Telling them apart would mean matching the client's resent history against stored transcripts.
- `GroupAttachmentBinder` stores group attachments without an owner, so they do not count towards the per-user quota.

```decision-log
| 2026-10-03 | /v1 requests without a chat id are keyed by a hash of their first system + first user message (`chat-key-fallback=history`), with `shared` as the opt-back | One `:default` conversation per user/agent shared context across unrelated chats and grew without bound | Always-new conversation per request (breaks history-less clients and memory); inferring restarts from message counts; honouring a caller-supplied EDDI conversation id (needs an ownership check, deferred) |
```
